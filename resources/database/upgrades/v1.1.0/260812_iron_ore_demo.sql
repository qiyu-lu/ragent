-- 260812 铁矿工业知识 Demo：文档版本、分块精确来源与候选任务生命周期。

ALTER TABLE t_knowledge_document ADD COLUMN IF NOT EXISTS document_key VARCHAR(256);
ALTER TABLE t_knowledge_document ADD COLUMN IF NOT EXISTS document_version VARCHAR(32);
ALTER TABLE t_knowledge_document ADD COLUMN IF NOT EXISTS demo_data SMALLINT NOT NULL DEFAULT 0;

UPDATE t_knowledge_document
SET document_key = regexp_replace(
        regexp_replace(doc_name, '\.[^.]+$', ''),
        '[[:space:]_.-]*[Vv][0-9]+(\.[0-9]+)*(-demo)?[[:space:]_.-]*$',
        '',
        'i')
WHERE document_key IS NULL OR btrim(document_key) = '';

ALTER TABLE t_knowledge_document ALTER COLUMN document_key SET NOT NULL;

COMMENT ON COLUMN t_knowledge_document.document_key IS '跨版本稳定文档键';
COMMENT ON COLUMN t_knowledge_document.document_version IS '文件名中声明的文档版本';
COMMENT ON COLUMN t_knowledge_document.demo_data IS '是否为演示构造数据';

ALTER TABLE t_knowledge_chunk
    ADD COLUMN IF NOT EXISTS metadata JSONB NOT NULL DEFAULT '{}'::jsonb;
COMMENT ON COLUMN t_knowledge_chunk.metadata IS '分块来源元数据，包括工作表和单元格范围';

CREATE TABLE IF NOT EXISTS t_iron_ore_task_template (
    id                VARCHAR(20)  NOT NULL PRIMARY KEY,
    conversation_id   VARCHAR(20)  NOT NULL,
    source_message_id VARCHAR(20)  NOT NULL,
    doc_id             VARCHAR(20)  NOT NULL,
    owner_user_id      VARCHAR(20)  NOT NULL,
    title              VARCHAR(256) NOT NULL,
    procedure_name     VARCHAR(256),
    document_version   VARCHAR(32),
    status             VARCHAR(16)  NOT NULL,
    template_data      JSONB        NOT NULL,
    evidence_refs      JSONB        NOT NULL DEFAULT '[]'::jsonb,
    approved_by        VARCHAR(64),
    approved_at        TIMESTAMP,
    created_by         VARCHAR(64),
    updated_by         VARCHAR(64),
    create_time        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted            SMALLINT     NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_iron_ore_task_source
    ON t_iron_ore_task_template (source_message_id, doc_id, owner_user_id)
    WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_iron_ore_task_conversation
    ON t_iron_ore_task_template (conversation_id, owner_user_id);
COMMENT ON TABLE t_iron_ore_task_template IS '铁矿演示候选任务模板';

CREATE TABLE IF NOT EXISTS t_iron_ore_task_execution (
    id               VARCHAR(20) NOT NULL PRIMARY KEY,
    task_template_id VARCHAR(20) NOT NULL,
    status           VARCHAR(32) NOT NULL,
    events           JSONB       NOT NULL DEFAULT '[]'::jsonb,
    start_time       TIMESTAMP,
    end_time         TIMESTAMP,
    created_by       VARCHAR(64),
    create_time      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_iron_ore_task_execution
    ON t_iron_ore_task_execution (task_template_id);
COMMENT ON TABLE t_iron_ore_task_execution IS '铁矿候选任务模拟执行记录';
