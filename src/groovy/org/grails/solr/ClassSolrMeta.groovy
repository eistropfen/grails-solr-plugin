package org.grails.solr

import org.apache.solr.common.SolrInputDocument


class ClassSolrMeta {
    private List<FieldSolrMeta> fields = []

    void addFieldMeta(FieldSolrMeta field) {
        fields << field
    }
}