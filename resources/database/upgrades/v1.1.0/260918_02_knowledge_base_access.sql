-- W4 knowledge-base access control. Apply after 260918_01_research_durable_execution.sql; additive and repeatable.
-- Existing knowledge bases become PUBLIC (everyone may read, as before); new rows default to PRIVATE.
-- Owners are backfilled from created_by (a username) where that user still exists.
ALTER TABLE t_knowledge_base ADD COLUMN IF NOT EXISTS owner_user_id VARCHAR(20);
ALTER TABLE t_knowledge_base ADD COLUMN IF NOT EXISTS visibility VARCHAR(16) NOT NULL DEFAULT 'PUBLIC';
ALTER TABLE t_knowledge_base ALTER COLUMN visibility SET DEFAULT 'PRIVATE';
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_kb_visibility') THEN
        ALTER TABLE t_knowledge_base ADD CONSTRAINT ck_kb_visibility CHECK (visibility IN ('PUBLIC', 'PRIVATE', 'RESTRICTED'));
    END IF;
END $$;
UPDATE t_knowledge_base kb SET owner_user_id = u.id
FROM t_user u
WHERE kb.owner_user_id IS NULL AND u.username = kb.created_by AND u.deleted = 0;
COMMENT ON COLUMN t_knowledge_base.owner_user_id IS '所有者用户ID，拥有管理权限';
COMMENT ON COLUMN t_knowledge_base.visibility IS '可见性：PUBLIC 全员可读 / PRIVATE 仅所有者与管理员 / RESTRICTED 所有者、管理员与授权对象';

CREATE TABLE IF NOT EXISTS t_knowledge_base_grant (
    id           VARCHAR(20) NOT NULL PRIMARY KEY,
    kb_id        VARCHAR(20) NOT NULL,
    subject_type VARCHAR(8)  NOT NULL,
    subject_id   VARCHAR(64) NOT NULL,
    permission   VARCHAR(8)  NOT NULL,
    created_by   VARCHAR(20),
    create_time  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_kb_grant_subject UNIQUE (kb_id, subject_type, subject_id),
    CONSTRAINT ck_kb_grant_subject_type CHECK (subject_type IN ('USER', 'ROLE')),
    CONSTRAINT ck_kb_grant_permission CHECK (permission IN ('READ', 'MANAGE'))
);
CREATE INDEX IF NOT EXISTS idx_kb_grant_subject ON t_knowledge_base_grant (subject_type, subject_id);
COMMENT ON TABLE t_knowledge_base_grant IS '知识库授权：PUBLIC 与 RESTRICTED 库生效，PRIVATE 库忽略';
COMMENT ON COLUMN t_knowledge_base_grant.subject_type IS '授权对象类型：USER 用户ID / ROLE 角色名';
COMMENT ON COLUMN t_knowledge_base_grant.permission IS 'READ 检索与查看 / MANAGE 另含知识库与文档的增删改';
