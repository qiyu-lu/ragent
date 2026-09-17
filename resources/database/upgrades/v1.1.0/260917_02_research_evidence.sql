-- P2: research run/evidence/event storage. Apply manually to existing PostgreSQL databases.
-- Re-running is safe; no historical business tables are dropped or modified.

CREATE TABLE IF NOT EXISTS t_research_run (
    id                  VARCHAR(64) PRIMARY KEY,
    owner_user_id       VARCHAR(64) NOT NULL,
    conversation_id     VARCHAR(64) NOT NULL,
    client_request_id   VARCHAR(128) NOT NULL,
    output_type         VARCHAR(16) NOT NULL CHECK (output_type IN ('REPORT', 'PLAN')),
    status              VARCHAR(32) NOT NULL DEFAULT 'QUEUED'
                        CHECK (status IN ('QUEUED', 'RUNNING', 'WAITING_INPUT', 'COMPLETED',
                                          'PARTIAL', 'FAILED', 'CANCELLED', 'INTERRUPTED')),
    brief               JSONB NOT NULL CHECK (jsonb_typeof(brief) = 'object'),
    state               JSONB NOT NULL DEFAULT '{}'::jsonb,
    artifact            JSONB,
    revision            BIGINT NOT NULL DEFAULT 0,
    epoch               BIGINT NOT NULL DEFAULT 0,
    lease_token         VARCHAR(64),
    lease_until         TIMESTAMPTZ,
    event_sequence      BIGINT NOT NULL DEFAULT 0,
    usage               JSONB NOT NULL DEFAULT '{}'::jsonb,
    error_summary       TEXT,
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    create_time         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ux_research_request UNIQUE (owner_user_id, client_request_id)
);
CREATE INDEX IF NOT EXISTS idx_research_run_owner ON t_research_run (owner_user_id, update_time);
COMMENT ON TABLE t_research_run IS '研究任务：归属、幂等请求与运行状态；P3 实现执行器';
COMMENT ON COLUMN t_research_run.brief IS '服务端确认的目标、约束及 allowedKbIds；模型不可扩大范围';
COMMENT ON COLUMN t_research_run.event_sequence IS 'P3 在短事务中原子分配事件序号，禁止 MAX(sequence_no)+1';
COMMENT ON COLUMN t_research_run.usage IS '真实供应商 usage 或显式 unknown；不能以默认 0 冒充免费';

CREATE TABLE IF NOT EXISTS t_research_evidence (
    evidence_id             VARCHAR(67) PRIMARY KEY,
    run_id                  VARCHAR(64) NOT NULL REFERENCES t_research_run(id),
    kb_id                   VARCHAR(64) NOT NULL,
    doc_id                  VARCHAR(64) NOT NULL,
    document_name           VARCHAR(255) NOT NULL,
    document_version        VARCHAR(128),
    chunk_ids               JSONB NOT NULL
                            CHECK (jsonb_typeof(chunk_ids) = 'array' AND jsonb_array_length(chunk_ids) > 0),
    content_hash            VARCHAR(64) NOT NULL,
    source_text             TEXT NOT NULL,
    text                    TEXT NOT NULL,
    source_location         JSONB NOT NULL DEFAULT '{}'::jsonb,
    source_metadata_hash    VARCHAR(64) NOT NULL,
    retrieved_by_task_id    VARCHAR(64) NOT NULL,
    truncated               BOOLEAN NOT NULL,
    read                    BOOLEAN NOT NULL DEFAULT FALSE,
    source_extent           VARCHAR(32) NOT NULL CHECK (source_extent IN ('CHUNK', 'AVAILABLE_EXCERPT')),
    create_time             TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time             TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_research_evidence_run ON t_research_evidence (run_id);
COMMENT ON TABLE t_research_evidence IS '研究证据快照；稳定 ID 包含 run、文档版本、块、正文与位置 hash';
COMMENT ON COLUMN t_research_evidence.source_text IS '首次回查的完整块快照；内部保存，不直接作为工具输出';
COMMENT ON COLUMN t_research_evidence.text IS '候选摘要或 read_source 实际提供的有长度限制的正文';
COMMENT ON COLUMN t_research_evidence.read IS '已通过 read_source 提供正文；检索命中不等于已读';
COMMENT ON COLUMN t_research_evidence.source_extent IS 'CHUNK 仅是块，AVAILABLE_EXCERPT 仅是可用段落，均不代表整篇全文';

CREATE TABLE IF NOT EXISTS t_research_event (
    run_id          VARCHAR(64) NOT NULL REFERENCES t_research_run(id),
    sequence_no     BIGINT NOT NULL CHECK (sequence_no > 0),
    task_id         VARCHAR(64) NOT NULL,
    event_type      VARCHAR(64) NOT NULL,
    summary         TEXT NOT NULL,
    payload         JSONB NOT NULL DEFAULT '{}'::jsonb,
    create_time     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (run_id, sequence_no)
);
COMMENT ON TABLE t_research_event IS '可展示的阶段和工具事件；不保存隐藏推理或逐 token 行';
