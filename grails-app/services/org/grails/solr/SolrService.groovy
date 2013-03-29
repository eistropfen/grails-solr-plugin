/*
* Copyright 2010 the original author or authors.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*
* ----------------------------------------------------------------------------
* Original Author: Mike Brevoort, http://mike.brevoort.com
* Project sponsored by:
*     Avalon Consulting LLC - http://avalonconsult.com
*     Patheos.com - http://patheos.com
* ----------------------------------------------------------------------------
*/

package org.grails.solr

import org.apache.solr.client.solrj.impl.CommonsHttpSolrServer
import org.apache.solr.client.solrj.impl.StreamingUpdateSolrServer
import org.apache.solr.client.solrj.impl.XMLResponseParser
import org.codehaus.groovy.grails.commons.ConfigurationHolder
import org.apache.solr.client.solrj.SolrQuery
import org.apache.solr.client.solrj.response.QueryResponse
import org.grails.solr.*
import org.codehaus.groovy.grails.commons.GrailsClassUtils
import org.apache.solr.common.SolrInputDocument
import org.codehaus.groovy.grails.commons.DomainClassArtefactHandler
import org.springframework.transaction.annotation.Transactional


class SolrService {

  static final int BATCH_SIZE = 100
  boolean transactional = false
  def grailsApplication
  private def servers = [:]

  /**
  * Return a SolrServer
  * {@link http://lucene.apache.org/solr/api/org/apache/solr/client/solrj/SolrServer.html}
  */
  def getServer() {
    def url =  (grailsApplication.config.solr?.url) ? grailsApplication.config.solr.url : "http://localhost:8983/solr"
    def server

    // only create a new CommonsHttpSolrServer once per URL since it is threadsafe and this service is a singleton
    // Ref: http://wiki.apache.org/solr/Solrj#CommonsHttpSolrServer
    if( !servers[url] ){
        servers[url] = new CommonsHttpSolrServer( url )
    }
    return servers[url]
  }

  def getStreamingUpdateServer(queueSize=20, numThreads=3) {
    def url =  (grailsApplication.config.solr?.url) ? grailsApplication.config.solr.url : "http://localhost:8983/solr"
    def server = new StreamingUpdateSolrServer( url, queueSize, numThreads)
    return server
  }

  /**
  * Execute a basic Solr query given a string formatted query
  *
  * @param query - the Solr query string
  * @return Map with 'resultList' - list of Maps representing results and 'queryResponse' - Solrj query result
  */
  def search(String query) {
    search( new SolrQuery( query ) )
  }

  /**
  * Given SolrQuery object, execute Solr query
  *
  * @param solrQuery - SolrQuery object representating the query {@link http://lucene.apache.org/solr/api/org/apache/solr/client/solrj/SolrQuery.html}
  * @return Map with 'resultList' - list of Maps representing results and 'queryResponse' - Solrj query result
  */
  def search(SolrQuery solrQuery) {
    QueryResponse rsp = getServer().query( solrQuery );


    def results = []
    rsp.getResults().each { doc ->
      def map = [:]
      doc.getFieldNames().each { it ->

        // add both the stripped field name and the actual solr field name to
        // the result map... little redundant but greater flexibilty in retrieving
        // results
        def strippedFieldName = SolrUtil.stripFieldName(it)
        map."${strippedFieldName}" = doc.getFieldValue(it)
        if(!map."${it}" && strippedFieldName != it)
          map."${it}" = doc.getFieldValue(it)
      }
      map.id = SolrUtil.parseSolrId(doc.getFieldValue("id"))?.id

      // Add the SolrDocument to the map as well
      // http://lucene.apache.org/solr/api/org/apache/solr/common/SolrDocument.html
      map.solrDocument = doc

      results << map
    }

    return new SearchResults(resultList: results, queryResponse: rsp);

  }

  /**
  * Constitute SolrQuery for a haversine based spatial search. This method returns
  * the SolrQuery object in case you need to manipulate it further (add facets, etc)
  *
  * @param query  a lucene formatted query to execute in addition to the location range query
  * @param lat    latitude in degrees
  * @param lng    longitude in degrees
  * @param range  the proximity range to filter results by (small the better performance). unit is miles unless the radius param is passed in which case it's whatever the unit of the radius is
  * @param start  result number of the first returned result - used in paging (optional, default: 0)
  * @param rows   number of results to include - aka page size (optional, default: 10)
  * @param sort   sort direction asc or desc (optional, default: asc)
  * @param funcQuery provide a function query to be summed with the hsin function (optional)
  * @param radius sphere radius for haversine algorigthm (optional, default: 3963.205 [earth radius in miles])
  * @param lat_field SOLR index field for latitude in radians (optional, default: latitude_d)
  * @param lng_field SOLR index field for latitude in radians (optional, default: longitude_d)
  * @return SolrQuery object representing this spatial query
  */
  SolrQuery getSpatialQuery(query, lat, lng, range, start=0, rows=10, sort="asc", funcQuery="", radius=3963.205, lat_field="latitude_rad_d", lng_field="longitude_rad_d") {
    def lat_rad = Math.toRadians( lat )
    def lng_rad = Math.toRadians( lng )
//@HL FIX
//    def hsin = "hsin(${lat_rad},${lng_rad},${lat_field},${lng_field},${radius})"
      def hsin = "hsin(${radius},false,${lat_rad},${lng_rad},${lat_field},${lng_field})"
      def order = [asc: SolrQuery.ORDER.asc, desc: SolrQuery.ORDER.desc]

    if(funcQuery != "")
      funcQuery = "${funcQuery},"

    SolrQuery solrQuery = new SolrQuery( (query && query.trim()) ? "(${query}) AND _val_:\"sum(${funcQuery}${hsin})\"" : "_val_:\"sum(${funcQuery}${hsin})\"" )
    solrQuery.addFilterQuery("{!frange l=0 u=${range}}${hsin}")
    solrQuery.setStart(start)
    solrQuery.setRows(rows)
    solrQuery.addSortField("score", order[sort])
    return solrQuery
  }

  /**
  * Same as getSpatialQuery but executes query
  * @return Map with 'resultList' - list of Maps representing results and 'queryResponse' - Solrj query result
  */
  def querySpatial(query, lat, lng, range, start=0, rows=10, sort="asc", funcQuery="", radius=3963.205, lat_field="latitude_rad_d", lng_field="longitude_rad_d") {
    def solrQuery = getSpatialQuery(query, lat, lng, range, start, rows, sort, funcQuery, radius, lat_field, lng_field)
    return querySpatial(solrQuery, lat, lng, lat_field, lng_field)
  }

  /**
  * Expected to be called after getSpatialQuery, assuming you need to further
  * manipulate the SolrQuery object before executing the query.
  * @return Map with 'resultList' - list of Maps representing results and 'queryResponse' - Solrj query result
  */
  def querySpatial(SolrQuery solrQuery, lat, lng, lat_field="latitude_rad_d", lng_field="longitude_rad_d") {
    log.debug ("spatial query: ${solrQuery}")
    def result = search(solrQuery)
    result.resultList.each {
      it.dist = Haversine.computeMi(lat, lng, Math.toDegrees(it."${SolrUtil.stripFieldName(lat_field)}"), Math.toDegrees(it."${SolrUtil.stripFieldName(lng_field)}"))
    }
    return result
  }

    def deleteAll() {
        def server = getServer()
        log.info("Deleting all document in Solr index")
        server?.deleteByQuery('*:*')
        server?.commit()
    }

  /**
   * Perform bulk indexing of domain objects.
   *   Iterate through each domain class and collect solr fields for each class.
   *   Use HQL to query for data from the collected field.
   *   Create solr document and submit to solr server.
   * @param domainClasses List of domain classes to perform indexing on, null means all existing classes
   * @return
   */
  @Transactional(readOnly = true)
  def index(List domainClasses = null, prefix = '') {
    def dcs = domainClasses
    def server = getServer()

    if (!dcs) {
      // delete old index
      deleteAll()
      dcs = grailsApplication.domainClasses
    }

    def myPackageName = this.class.name - this.class.simpleName

    dcs.each { dc ->
      def dcz = dc.clazz
      if (!dcz.name.startsWith(myPackageName) && (GrailsClassUtils.getStaticPropertyValue(dc.clazz, "enableSolrSearch"))) {
        def meta = new ClassSolrMeta()
        def solrExtractors = grailsApplication.mainContext.getBean("solrExtractors")
        solrExtractors.each { extractor ->
          def innerMeta = extractor.extractSolrMeta(dcz, '', '')
          meta.fields += innerMeta.fields
        }

        Set outerJoinList = []
        def fields = meta?.fields?.collect { field ->
          if (field.parentPropertyName) {
             outerJoinList << field.parentPropertyName
          }
          field.parentPropertyName ?
              "${field.parentPropertyName}.${field.propertyName}" : field.propertyName
        }

        def columnsString = fields.collect { "t.${it}" }.join(", ")
        def query = "select t.id, ${columnsString} from ${dcz.name} t ${outerJoinList?.collect { "left outer join t.${it}"}.join(" ")}"
        log.trace("Query: ${query}")
        def results = dcz.executeQuery(query)
        log.trace("Executed '${query}' and got ${results?.size()} results")
        results.eachWithIndex { result, index ->
          def doc = new SolrInputDocument();

          result.eachWithIndex { fieldValue, fieldIdx ->
            if (fieldIdx != 0) { // skip id field
              def fieldMeta = meta?.fields[fieldIdx - 1]
              SolrUtil.addFieldToDoc(doc, fieldMeta, fieldValue, prefix)
            }
          }

          // add a field to the index for the field type
          SolrUtil.addTypeToDoc(doc, dcz, prefix)

          // add a field for the id which will be the classname dash id
          SolrUtil.addIdToDoc(doc, dcz, result.getAt(0), prefix)

          server.add(doc)

          if (index % BATCH_SIZE == 0 || (index + 1) == results.size()) {
            log.trace "[${new Date().time}] - indexing ${index} of ${dcz.name}"
            server.commit()
          }
        }
      }
    }
  }
}
