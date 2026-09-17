-- P2 second batch. Apply after 260917_02_research_evidence.sql to existing PostgreSQL databases.
-- Additive and repeatable; previously saved single-chunk snapshots remain unchanged.
ALTER TABLE t_research_evidence
    ADD COLUMN IF NOT EXISTS origin_evidence_id VARCHAR(67) REFERENCES t_research_evidence(evidence_id);
CREATE UNIQUE INDEX IF NOT EXISTS ux_research_evidence_origin
    ON t_research_evidence (run_id, origin_evidence_id) WHERE origin_evidence_id IS NOT NULL;
COMMENT ON COLUMN t_research_evidence.origin_evidence_id IS '邻接证据关联原候选；同一候选仅保存首次展开快照';
