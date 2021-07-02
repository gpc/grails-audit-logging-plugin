grails {
    plugin {
        auditLog {
            verbose = true
            excluded = ['version', 'lastUpdated', 'lastUpdatedBy']
            logFullClassName = true
            failOnError = true
            mask = ['ssn']
            logIds = true
            defaultActor = 'SYS'
            useDatasource = 'second' // store in "second" datasource
            replacementPatterns = ["a.b": ""]
            truncateLength = 1000000
            includePackages = [ 'test', 'other.one' ]
            excludePackages = [ 'not.this.package', 'or.this.one' ]
        }
    }
}

// Added by the Audit-Logging plugin:
grails.plugin.auditLog.auditDomainClassName = 'test.AuditTrail'

hibernate_second {
    cache.use_second_level_cache = true
    cache.use_query_cache = false
    cache.region.factory_class = 'org.hibernate.cache.ehcache.EhCacheRegionFactory'
}
