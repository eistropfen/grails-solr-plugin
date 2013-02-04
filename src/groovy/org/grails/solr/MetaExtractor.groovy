package org.grails.solr

interface MetaExtractor {
    ClassSolrMeta extractSolrMeta(Class dcz, String parentPropertyName, String solrFieldPrefix)
    String getFilterQuery(Class clazz)
}
