/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
*/
package grails.plugins.orm.auditable

import grails.core.GrailsApplication
import grails.plugins.orm.auditable.resolvers.AuditRequestResolver
import groovy.transform.CompileStatic
import groovy.transform.Memoized
import groovy.util.logging.Slf4j
import org.grails.datastore.gorm.GormEnhancer
import org.grails.datastore.gorm.GormEntity
import org.grails.datastore.mapping.core.Datastore
import org.grails.datastore.mapping.engine.event.AbstractPersistenceEvent
import org.grails.datastore.mapping.engine.event.AbstractPersistenceEventListener
import org.grails.datastore.mapping.engine.event.EventType
import org.grails.datastore.mapping.engine.event.PostInsertEvent
import org.grails.datastore.mapping.engine.event.PreDeleteEvent
import org.grails.datastore.mapping.engine.event.PreUpdateEvent
import org.grails.datastore.mapping.model.PersistentEntity
import org.springframework.context.ApplicationEvent

import static grails.plugins.orm.auditable.AuditLogListenerUtil.*

/**
 * Grails interceptor for logging saves, updates, deletes and acting on
 * individual properties changes and delegating calls back to the Domain Class
 */
@Slf4j
@CompileStatic
class AuditLogListener extends AbstractPersistenceEventListener {
    private GrailsApplication grailsApplication
    private Integer truncateLength
    @Lazy
    AuditRequestResolver requestResolver = { grailsApplication.mainContext.getBean(AuditRequestResolver) }()

    AuditLogListener(Datastore datastore, GrailsApplication grailsApplication) {
        super(datastore)
        this.grailsApplication = grailsApplication
        this.truncateLength = determineTruncateLength()
    }

    @Override
    protected void onPersistenceEvent(AbstractPersistenceEvent event) {
        if (event.source != datastore) {
            log.trace("Event received for datastore {}, ignoring", event.source)
            return
        }
        if (!(event.entityObject instanceof Auditable)) {
            return
        }

        // Logging is disabled
        if (AuditLogContext.context.disabled) {
            return
        }

        try {
            AuditEventType auditEventType = AuditEventType.forEventType(event.eventType)
            Auditable domain = event.entityObject as Auditable

            if (domain.isAuditable(auditEventType)) {
                log.trace("Audit logging: Event {} for object {}", auditEventType, event.entityObject.class.name)

                switch (event.eventType) {
                    case EventType.PostInsert:
                    case EventType.PreDelete:
                        handleInsertAndDelete(domain, auditEventType)
                        break
                    case EventType.PreUpdate:
                        handleUpdate(domain, auditEventType)
                        break
                }
            }
        }
        catch (Exception e) {
            if (AuditLogContext.context.failOnError) {
                throw e
            } else {
                log.error("Error creating audit log for event ${event.eventType} and domain ${event.entityObject}", e)
            }
        }
    }

    @Override
    boolean supportsEventType(Class<? extends ApplicationEvent> eventType) {
        return eventType.isAssignableFrom(PostInsertEvent) ||
            eventType.isAssignableFrom(PreUpdateEvent) ||
            eventType.isAssignableFrom(PreDeleteEvent)
    }

    @Memoized
    protected Collection<String> getAuditablePropertyNames(Class<?> domainClass) {
        Audit audit = domainClass.getAnnotation(Audit)
        Set<String> excluded = null
        if (audit) {
            if (audit.includes().length > 0) return Arrays.asList(audit.includes())
            if (audit.excludes().length > 0) excluded = Arrays.asList(audit.excludes()).toSet()
        }
        Set<String> configExcluded = AuditLoggingConfigUtils.auditConfig.excluded as Set<String>
        excluded = excluded ? configExcluded ? excluded + configExcluded : excluded : configExcluded

        PersistentEntity persistentEntity = GormEnhancer.findStaticApi(domainClass)?.getGormPersistentEntity()
        if (persistentEntity) {
            Set<String> persistentProperties = persistentEntity.persistentProperties*.name as Set<String>
            Set<String> loggedProperties = persistentProperties - excluded

            return loggedProperties
        }
        return Arrays.asList([] as String[])
    }


    /**
     * We must use the preDelete event if we want to capture what the old object was like.
     */
    protected void handleInsertAndDelete(Auditable domain, AuditEventType auditEventType) {
        if (domain.logIgnoreEvents?.contains(auditEventType)) {
            return
        }

        Map<String, Object> map = [:]

        // If verbose logging, resolve all properties (dirty doesn't really matter here)
        boolean verbose = isVerboseEnabled(domain, auditEventType)
        if (verbose) {
            Collection<String> loggedProperties = getAuditablePropertyNames(domain.class)
            if (loggedProperties) {
                map = makeMap(loggedProperties, domain)
            }
        }

        if (map || !verbose) {
            if (auditEventType == AuditEventType.DELETE) {
                logChanges(domain, [:], map, auditEventType)
            } else {
                logChanges(domain, map, [:], auditEventType)
            }
        }
    }

    /**
     * Look at persistent and transient state for the object and log differences
     */
    protected void handleUpdate(Auditable domain, AuditEventType auditEventType) {
        if (domain.logIgnoreEvents?.contains(auditEventType)) {
            return
        }

        // By default, we don't log verbose properties
        Map<String, Object> newMap = [:]
        Map<String, Object> oldMap = [:]

        // If verbose, resolve properties
        boolean verbose = isVerboseEnabled(domain, auditEventType)
        if (verbose) {
            Collection<String> dirtyProperties = domain.getAuditableDirtyPropertyNames()

            // Needs to be dirty properties and properties to log, otherwise it won't be verbose
            if (dirtyProperties) {

                // Get the prior values for everything that is dirty
                oldMap = dirtyProperties.collectEntries { String property -> [property, getPersistentValue(domain, property)] }

                // Get the current values for everything that is dirty
                newMap = makeMap(dirtyProperties, domain)
            }
        }

        if (newMap || oldMap || !verbose) {
            logChanges(domain, newMap, oldMap, auditEventType)
        }
    }

    /**
     * Do the actual logging of changes
     *
     * @param domain the thing triggering the audit
     * @param newMap for insert and delete, holds the current values filtered to what we care about
     * @param oldMap null for insert and delete, for updates holds the original persisted values filtered to what we care about
     * @param eventType the type of event we are logging
     */
    protected void logChanges(Auditable domain, Map<String, Object> newMap, Map<String, Object> oldMap, AuditEventType eventType) {
        log.debug("Audit logging event {} and domain {}", eventType, domain.getClass().name)

        // Wrap all of the logging in a single session to prevent flushing for each insert
        getAuditDomainClass().invokeMethod("withNewSession") { Object session ->
            Long persistedObjectVersion = getPersistedObjectVersion(domain, newMap, oldMap)

            // This handles insert, delete, and update with any property level logging enabled
            if (newMap || oldMap) {
                Set<String> allPropertyNames = (newMap.keySet() + oldMap.keySet())
                allPropertyNames.each { String propertyName ->
                    String newValueAsString = null
                    String oldValueAsString = null

                    // This indicates a change
                    Object newVal = newMap[propertyName]
                    if (newVal != null) {
                        newValueAsString = conditionallyMaskAndTruncate(domain, propertyName, domain.convertLoggedPropertyToString(propertyName, newVal), truncateLength)
                    }
                    Object oldVal = oldMap[propertyName]
                    if (newVal != oldVal) {
                        oldValueAsString = conditionallyMaskAndTruncate(domain, propertyName, domain.convertLoggedPropertyToString(propertyName, oldVal), truncateLength)
                    }

                    // Create a new entity for each property
                    GormEntity audit = createAuditLogDomainInstance(
                        actor: requestResolver?.currentActor ?: 'N/A',
                        uri: requestResolver?.currentURI,
                        className: domain.logClassName,
                        eventName: eventType.name(),
                        persistedObjectId: domain.logEntityId,
                        persistedObjectVersion: persistedObjectVersion,
                        propertyName: propertyName,
                        oldValue: oldValueAsString,
                        newValue: newValueAsString,
                    )
                    if (domain.beforeSaveLog(audit)) {
                        audit.save(failOnError: true)
                    }
                }
            } else {
                // Create a single entity for this event
                GormEntity audit = createAuditLogDomainInstance(
                    actor: requestResolver?.currentActor ?: 'N/A',
                    uri: requestResolver?.currentURI,
                    className: domain.logClassName,
                    eventName: eventType.name(),
                    persistedObjectId: domain.logEntityId,
                    persistedObjectVersion: persistedObjectVersion
                )
                if (domain.beforeSaveLog(audit)) {
                    audit.save(failOnError: true)
                }
            }

            // Flush the session once after all the audit insert(s)
            session.invokeMethod("flush", null)
        }
    }

    protected Long getPersistedObjectVersion(Auditable domain, Map<String, Object> newMap, Map<String, Object> oldMap) {
        def persistedObjectVersion = (newMap?.version ?: oldMap?.version)
        if (!persistedObjectVersion && domain.hasProperty("version")) {
            persistedObjectVersion = domain.metaClass.getProperty(domain, "version")
        }
        persistedObjectVersion as Long
    }

    protected boolean isVerboseEnabled(Auditable domain, AuditEventType eventType) {
        AuditLogContext.context.verbose || domain.logVerboseEvents?.contains(eventType)
    }
}
