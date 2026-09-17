-- Run explicitly with psql. Existing historical upgrades remain unchanged.
CREATE TABLE IF NOT EXISTS t_research_corpus_document (
    kb_id VARCHAR(20) NOT NULL REFERENCES t_knowledge_base(id),
    source_document_id VARCHAR(256) NOT NULL,
    doc_id VARCHAR(20) NOT NULL UNIQUE REFERENCES t_knowledge_document(id),
    import_hash VARCHAR(64) NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (kb_id, source_document_id)
);
-- Existing PG sink performs a document-scoped delete before replacement.
CREATE INDEX IF NOT EXISTS idx_vector_collection_doc ON t_knowledge_vector (collection_name, (metadata->>'doc_id'));
