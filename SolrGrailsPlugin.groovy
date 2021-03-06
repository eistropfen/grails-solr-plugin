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

import org.codehaus.groovy.grails.commons.metaclass.*
import org.codehaus.groovy.grails.commons.GrailsApplication
import org.apachedomainDesc.solr.client.solrj.impl.*
import org.apache.solr.common.*
import org.apache.solr.client.solrj.response.QueryResponse
import org.apache.solr.client.solrj.SolrQuery

import org.codehaus.groovy.grails.commons.ApplicationHolder
import org.codehaus.groovy.grails.commons.DomainClassArtefactHandler
import org.codehaus.groovy.grails.commons.GrailsClassUtils

import org.grails.solr.SolrIndexListener
import org.grails.solr.Solr
import org.grails.solr.SolrUtil
import grails.util.Environment
import org.grails.solr.SolrMetaExtractor
import org.grails.solr.ClassSolrMeta
import org.apache.log4j.Logger

class SolrGrailsPlugin {
    // the plugin version
    def version = "0.4"
    // the version or versions of Grails the plugin is designed for
    def grailsVersion = "1.1 > *"
    // the other plugins this plugin depends on
    def dependsOn = [domainClass: '1.1 > *'] //, hibernate: '1.1 > *']
    // resources that are excluded from plugin packaging
    def pluginExcludes = [
      "grails-app/views/error.gsp",
      "grails-app/domain/**",
      "grails-app/Config.groovy",
      "grials-app/UrlMappings.groovy",
      "grails-app/Datasource.groovy"
    ]

    def watchedResources = "file:./grails-app/domain/*"


    //static loadAfter = ['hibernate']

    // TODO Fill in these fields
    def author = "Mike Brevoort"
    def authorEmail = "brevoortm@avalonconsult.com"
    def title = "Grails Solr Plugin"
    def description = '''\\
Provides search capabilities for Grails domain model and more using the excellent Solr 
open source search server through the SolrJ library.
'''

    // URL to the plugin's documentation
    def documentation = "http://grails.org/Solr+Plugin"

    def doWithSpring = {
//      GroovyClassLoader classLoader = new GroovyClassLoader(getClass().getClassLoader())
//      ConfigObject config
//      try {
//         config = new ConfigSlurper().parse(classLoader.loadClass('SolrGrailsPluginConfig'))
//
//         }
//      } catch (Exception e) {/* swallow and use default */}

      xmlns util:"http://www.springframework.org/schema/util"

      defaultSolrMetaExtractor(SolrMetaExtractor)

      util.list(id: 'solrExtractors') {
        ref(bean:'defaultSolrMetaExtractor')
      }
    }

    def doWithApplicationContext = { applicationContext ->
      //@HL CONFIG
      if (application.config.solr.server.unavailable) return
      if (Environment.current != Environment.TEST) {
        // add the event listeners for reindexing on change
        def listeners = applicationContext.sessionFactory.eventListeners
        def listener = new SolrIndexListener()

        ['postInsert', 'postUpdate', 'postDelete'].each({
           addEventTypeListener(listeners, listener, it)
        })
      }
    }

    def doWithWebDescriptor = { xml ->
        // TODO Implement additions to web.xml (optional)
    }

    def doWithDynamicMethods = { ctx ->

      application.domainClasses.each { dc ->
          addDynamicMethodsToDomain(dc, application)

      } //domainClass.each
    } //doWithDynamicMethods

    def onChange = { event ->
        if (event.application.isArtefactOfType(DomainClassArtefactHandler.TYPE, event.source)) {
            addDynamicMethodsToDomain(event.source, event.application)
        }
    }



    private addDynamicMethodsToDomain(dc, application){
        def ctx = application.mainContext
        // only watching domain classes for now, so create a new Domain Class out of the reloaded class
        if (dc instanceof Class) {
            dc = application.addArtefact(DomainClassArtefactHandler.TYPE, dc)
        }

        if(GrailsClassUtils.getStaticPropertyValue(dc.clazz, "enableSolrSearch")) {
            def domainDesc = application.getArtefact(DomainClassArtefactHandler.TYPE, dc.clazz.name)
            def solrExplicitFieldAnnotation = GrailsClassUtils.getStaticPropertyValue(dc.clazz, "solrExplicitFieldAnnotation")

            // define indexSolr() method for all domain classes
            dc.metaClass.indexSolr << { server = null ->
                def delegateDomainOjbect = delegate
                def solrService = ctx.getBean("solrService");
                if(!server)
                    server = solrService.getServer()


                // TODO - is there a bette way to ignore built in parameters?

                // create a new solr document
                def doc = new SolrInputDocument();

                indexDomain(application, delegateDomainOjbect, doc)

                server.add(doc)
                server.commit()

            }

            // add deleteSolr method to domain classes
            dc.metaClass.deleteSolr << { ->
                def solrService = ctx.getBean("solrService");
                def server = solrService.getServer()
                server.deleteByQuery( "id:${delegate.class.name}-${delegate.id}");
                server.commit()
            }

            // add deleteSolr method to domain classes
            /*
            dc.metaClass.addSolr << { ->
              def solrService = ctx.getBean("solrService");
              def server = solrService.getServer

              server.addBean( delegate );
              server.commit()
            }
            */

            // add solrId method to domain classes
            dc.metaClass.solrId << { ->
                def solrService = ctx.getBean("solrService");
                SolrUtil.getSolrId(delegate)
            }

            dc.metaClass.'static'.solrFieldName << { name ->
                def delegateDomainOjbect = delegate
                def prefix = ""
                def solrFieldName
                def clazz = (delegate.class.name == 'java.lang.Class') ? delegate : delegate.class
                def prop = clazz.declaredFields.find{ field -> field.name == name}

                if(!prop && name.contains(".")) {
                    prefix = name[0..name.lastIndexOf('.')]
                    name = name.substring(name.lastIndexOf('.')+1)
                    List splitName = name.split(/\./)
                    splitName.remove(splitName.size()-1)
                    splitName.each {
                        //println "Before: ${delegateDomainOjbect}   ${it}"
                        delegateDomainOjbect = delegateDomainOjbect."${it}"
                        //println "After ${delegateDomainOjbect}"
                    }

                    prop = clazz.declaredFields.find{ field -> field.name == name}
                }

                def typeMap = SolrUtil.typeMapping["${prop?.type}"]
                solrFieldName = (typeMap) ? "${prefix}${name}${typeMap}" : "${prefix}${name}"

                // check for annotations
                if(prop?.isAnnotationPresent(Solr)) {
                    def anno = prop.getAnnotation(Solr)
                    if(anno.field())
                        solrFieldName = prop.getAnnotation(Solr).field()
                    else if(anno.asText())
                        solrFieldName = "${prefix}${name}_t"
                    else if(anno.ignore())
                        solrFieldName = null;
                } else if (solrExplicitFieldAnnotation) {
                    solrFieldName = null
                }

                return solrFieldName
            }

            dc.metaClass.'static'.searchSolr << { query ->
                def solrService = ctx.getBean("solrService");
                def solrExtractors = ctx.getBean("solrExtractors")
                def server = solrService.getServer()
                def solrQuery = (query instanceof org.apache.solr.client.solrj.SolrQuery) ? query : new SolrQuery( query )
                def objType = (delegate.class.name == 'java.lang.Class') ? delegate.name : delegate.class.name
                solrExtractors.each { extractor ->
                    def fq = extractor.getFilterQuery(objType)
                    if (fq) {
                        solrQuery.addFilterQuery(fq)
                    }
                }
                def result = solrService.search(solrQuery)

                return result
            }

        } // if enable solr search
    }

    def onConfigChange = { event ->
        // TODO Implement code that is executed when the project configuration changes.
        // The event is the same as for 'onChange'.
    }

  // copied from http://hartsock.blogspot.com/2008/04/inside-hibernate-events-and-audit.html
  private addEventTypeListener(listeners, listener, type) {
        def typeProperty = "${type}EventListeners"
        def typeListeners = listeners."${typeProperty}"

        def expandedTypeListeners = new Object[typeListeners.length + 1]
        System.arraycopy(typeListeners, 0, expandedTypeListeners, 0, typeListeners.length)
        expandedTypeListeners[-1] = listener

        listeners."${typeProperty}" = expandedTypeListeners
    }

  private indexDomain(application, delegateDomainObject, doc, depth = 1, prefix = "") {
    def clazz = (delegateDomainObject.class.name == 'java.lang.Class') ? delegateDomainObject : delegateDomainObject.class

    def meta = new ClassSolrMeta()
    def solrExtractors = application.mainContext.getBean("solrExtractors")
    solrExtractors.each { extractor ->
      def innerMeta = extractor.extractSolrMeta(clazz, '', '')
      meta.fields += innerMeta.fields
    }

    meta?.fields.each { field ->
      def fullPropName = field.fullPropertyName
      def nestedProperties = fullPropName.tokenize('.')
      def value = delegateDomainObject.properties[nestedProperties.remove(0)]
      while (nestedProperties) {
        def propName = nestedProperties.remove(0)
        value = value?.properties?.get(propName)
      }
      SolrUtil.addFieldToDoc(doc, field, value, prefix)
    }

    // add a field to the index for the field ype
    SolrUtil.addTypeToDoc(doc, clazz, prefix)

    // add a field for the id which will be the classname dash id
    SolrUtil.addIdToDoc(doc, clazz, delegateDomainObject.id, prefix)

    if(doc.getField(SolrUtil.TITLE_FIELD) == null) {
      def solrTitleMethod = delegateDomainObject.metaClass.pickMethod("solrTitle")
      def solrTitle = (solrTitleMethod != null) ? solrTitleMethod.invoke(delegateDomainObject) : delegateDomainObject.toString()
      doc.addField(SolrUtil.TITLE_FIELD, solrTitle)
    }
  } // indexDomain

}
