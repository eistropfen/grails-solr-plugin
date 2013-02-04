package org.grails.solr


class FieldSolrMeta {
    String parentPropertyName
    String solrFieldPrefix
    String propertyName
    String solrFieldName
    boolean asTextAlso

    String getFullSolrFieldName() {
        "${solrFieldPrefix}${solrFieldName}"
    }

    String getFullPropertyName() {
        parentPropertyName ? "${parentPropertyName}.${propertyName}" : propertyName
    }
}