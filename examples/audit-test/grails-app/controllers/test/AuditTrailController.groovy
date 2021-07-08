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

package test

import grails.gorm.transactions.Transactional

@Transactional("second")
class AuditTrailController {

  // the delete, save and update actions only accept POST requests
  static allowedMethods = [delete: 'POST', save: 'POST', update: 'POST']

  def index(Integer max) {
    params.max = Math.min(max ?: 10, 100)
    respond AuditTrail.list(params), model:[auditTrailCount: AuditTrail.count()]
  }

  def show(AuditTrail auditTrail) {
    respond auditTrail
  }

  def delete() {
    redirect(action: 'index')
  }

  def edit() {
    redirect(action: 'index')
  }

  def update() {
    redirect(action: 'index')
  }

  def create() {
    redirect(action: 'index')
  }

  def save() {
    redirect(action: 'index')
  }

  Object search(String query) {
    params.max = Math.min(params.max ?: 10, 100)
    def auditTrails = AuditTrail.forQuery(query).forDateCreated(params.searchByDate ? params.dateCreated : null).list(params)
    render(view: "index", model: [auditTrailList:auditTrails,  query:query, byDate:params.searchByDate, auditTrailCount: auditTrails.size()])
  }
}