package org.grails.solr

import org.apache.solr.common.SolrInputDocument
import org.codehaus.groovy.grails.commons.DomainClassArtefactHandler
import org.codehaus.groovy.grails.commons.GrailsClassUtils
import org.apache.log4j.Logger

class SolrMetaExtractor implements MetaExtractor {
    static Logger LOG = Logger.getLogger(SolrMetaExtractor)
    Map<Class, ClassSolrMeta> cacheClassSolrMeta = [:]

    ClassSolrMeta extractSolrMeta(Class dcz, String parentPropertyName, String solrFieldPrefix = '') {
        LOG.trace("extractSolrMeta - start [dcz:${dcz}]")
        def meta = new SolrMetaExtractor().extractSolrMetaHelper(new ClassSolrMeta(), dcz, parentPropertyName, solrFieldPrefix, false)
        LOG.trace("extractSolrMeta - end")

        return meta
    }

    private ClassSolrMeta extractSolrMetaHelper(ClassSolrMeta meta, Class dcz, String parentPropertyName, String solrFieldPrefix = '', boolean globalAsTextAlso) {
        LOG.trace("extractSolrMetaHelper - start [dcz:${dcz}]")

        if(!GrailsClassUtils.getStaticPropertyValue(dcz, "enableSolrSearch")) {
            LOG.trace("extractSolrMetaHelper - end [dcz:${dcz} - not enabled]")
            return meta
        }

        ClassSolrMeta cached = cacheClassSolrMeta.get(dcz)
        if (cached) {
            LOG.trace("I have process this class:${dcz} before ---")
        }

        dcz.declaredFields.each { prop ->
            LOG.trace("extractSolrMetaHelper - checking prop:${prop.name}")
            if (!SolrUtil.IGNORED_PROPS.contains(prop.name) && prop.type != java.lang.Object) {
                LOG.trace("extractSolrMetaHelper - not ignored prop:${prop.name}")

                def solrFieldName = dcz.solrFieldName(prop.name);
                if (solrFieldName) {
                    LOG.trace("extractSolrMetaHelper - found solrFieldName for prop:${prop.name} - solrFieldName:${solrFieldName}")

                    // processing @Solr(asTextAlso)
                    def asTextAlso = false
                    def clazzProp = dcz.declaredFields.find { field -> field.name == prop.name}
                    if (clazzProp.isAnnotationPresent(Solr) && clazzProp.getAnnotation(Solr).asTextAlso()) {
                        asTextAlso = true
                    }

                    // processing @Solr(prefix)
                    def prefix = ''
                    if (clazzProp.isAnnotationPresent(Solr) && clazzProp.getAnnotation(Solr).prefix()) {
                        prefix = clazzProp.getAnnotation(Solr).prefix()
                    }

                    // processing @Solr(component)
                    def isComponent = false
                    if (clazzProp.isAnnotationPresent(Solr) && DomainClassArtefactHandler.isDomainClass(prop.type) &&
                            clazzProp.getAnnotation(Solr).component()) {

                        // TODO: selective field processing (with fields property in @Solr)
                        def parentPropName = (parentPropertyName ? "${parentPropertyName}.${prop.name}" : prop.name)
                        extractSolrMetaHelper(meta, prop.type, parentPropName, solrFieldPrefix + prefix, asTextAlso)
                    } else {
                        def metaField = new FieldSolrMeta()
                        metaField.parentPropertyName = parentPropertyName
                        metaField.solrFieldPrefix = solrFieldPrefix
                        metaField.solrFieldName = solrFieldName
                        metaField.asTextAlso = asTextAlso || globalAsTextAlso
                        metaField.propertyName = prop.name
                        meta.addFieldMeta(metaField)
                    }
                }
            }
        }
        cacheClassSolrMeta.put(dcz, meta)
        LOG.trace("extractSolrMetaHelper - end")
        return meta
    }

    String getFilterQuery(Class clazz) {
        return "${SolrUtil.TYPE_FIELD}:${clazz.name}"
    }
}
