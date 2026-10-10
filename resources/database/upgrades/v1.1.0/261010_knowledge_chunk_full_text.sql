-- knowledge-quality 阶段 3：中文全文通道。增量、可重复执行；只加列、索引与两张统计表，不回填：
-- 存量用 POST /admin/full-text/rebuild 回填，之后由入库与单块编辑维护（rag.search.channels.full-text.enabled=true 时）。
ALTER TABLE t_knowledge_chunk ADD COLUMN IF NOT EXISTS content_tsv tsvector;
CREATE INDEX IF NOT EXISTS idx_chunk_content_tsv ON t_knowledge_chunk USING gin (content_tsv);
CREATE INDEX IF NOT EXISTS idx_chunk_kb_id ON t_knowledge_chunk (kb_id);
COMMENT ON COLUMN t_knowledge_chunk.content_tsv IS '全文索引：文档名 + 向量文本经应用侧分词（jieba + 术语表）后的带位置词项，直接写字面量，不经 PostgreSQL 解析器';

CREATE TABLE IF NOT EXISTS t_kq_term_stats (
    kb_id    VARCHAR(20) NOT NULL,
    term     TEXT        NOT NULL,
    doc_freq INTEGER     NOT NULL,
    CONSTRAINT pk_kq_term_stats PRIMARY KEY (kb_id, term)
);
COMMENT ON TABLE t_kq_term_stats IS '全文通道的词项文档频率（按知识库，只计未删除且启用的块），BM25 的 IDF 用；入库、删除与重建后整库重算';

CREATE TABLE IF NOT EXISTS t_kq_kb_stats (
    kb_id       VARCHAR(20) NOT NULL,
    chunk_count INTEGER     NOT NULL,
    total_terms BIGINT      NOT NULL,
    update_time TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_kq_kb_stats PRIMARY KEY (kb_id)
);
COMMENT ON TABLE t_kq_kb_stats IS '全文通道的语料统计（按知识库）：参与检索的块数与词项位置总数，BM25 的平均长度用';
