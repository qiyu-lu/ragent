-- W5 content-addressed embedding reuse. Apply after 260918_02_knowledge_base_access.sql; additive and repeatable.
-- A vector is reusable only for the same model and dimension, so both are part of the key. The table holds
-- no knowledge-base or document columns: a hit returns a vector to a caller that already holds the text.
CREATE TABLE IF NOT EXISTS t_embedding_cache (
    model_id    VARCHAR(160) NOT NULL,
    dimension   INTEGER      NOT NULL,
    text_sha256 VARCHAR(64)  NOT NULL,
    embedding   REAL[]       NOT NULL,
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_embedding_cache PRIMARY KEY (model_id, dimension, text_sha256),
    CONSTRAINT ck_embedding_cache_dimension CHECK (cardinality(embedding) = dimension)
);
CREATE INDEX IF NOT EXISTS idx_embedding_cache_last_used ON t_embedding_cache (last_used);
COMMENT ON TABLE t_embedding_cache IS '内容寻址的嵌入缓存：同模型、同维度、同向量文本复用向量';
COMMENT ON COLUMN t_embedding_cache.model_id IS '供应商:模型名（解析后的真实模型，不是候选别名）';
COMMENT ON COLUMN t_embedding_cache.text_sha256 IS '向量文本 UTF-8 的 SHA-256';
COMMENT ON COLUMN t_embedding_cache.last_used IS '最近一次写入或命中，容量超限时按它淘汰';

ALTER TABLE t_knowledge_document_chunk_log ADD COLUMN IF NOT EXISTS embed_cache_hits INTEGER;
ALTER TABLE t_knowledge_document_chunk_log ADD COLUMN IF NOT EXISTS embed_cache_misses INTEGER;
COMMENT ON COLUMN t_knowledge_document_chunk_log.embed_cache_hits IS '向量取自嵌入缓存的块数';
COMMENT ON COLUMN t_knowledge_document_chunk_log.embed_cache_misses IS '未命中缓存的块数（同批重复文本只上送一次）';
