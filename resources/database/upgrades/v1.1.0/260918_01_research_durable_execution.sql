-- W2 durable execution. Apply after 260917_04_research_corpus.sql to existing PostgreSQL databases.
-- Additive and repeatable; existing runs keep their status, and INTERRUPTED stays readable for old rows.
ALTER TABLE t_research_run ADD COLUMN IF NOT EXISTS executor_id VARCHAR(128);
ALTER TABLE t_research_run ADD COLUMN IF NOT EXISTS takeover_count INTEGER NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_research_run_pollable ON t_research_run (lease_until, update_time)
    WHERE status IN ('QUEUED', 'RUNNING');
COMMENT ON COLUMN t_research_run.lease_until IS '执行租约到期时间；持有者定时续租，过期后任一实例可接管';
COMMENT ON COLUMN t_research_run.executor_id IS '当前或最后一次持有租约的执行实例，仅用于诊断，写保护依赖 epoch 与 lease_token';
COMMENT ON COLUMN t_research_run.takeover_count IS '执行者失联后被接管的次数；超过上限判为毒任务 FAILED(EXECUTOR_LOST)';
