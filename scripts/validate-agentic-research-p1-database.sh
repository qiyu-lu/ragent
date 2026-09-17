#!/usr/bin/env bash
set -euo pipefail

# Requires a running pgvector/PostgreSQL development container.
# Only the database successfully created by this invocation is removed.
p1_repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd -- "$p1_repo_root"
p1_container=${P1_POSTGRES_CONTAINER:-ragent-iron-ore-dev-postgres-1}
p1_validation_db="research_p1_$(date -u +%Y%m%d%H%M%S)_${RANDOM}"
p1_db_created=false

cleanup_p1() {
  if [ "$p1_db_created" = true ]; then
    docker exec "$p1_container" sh -c 'exec dropdb -U "$POSTGRES_USER" "$1"' sh "$p1_validation_db"
    echo 'Temporary validation database removed.'
  fi
}
trap cleanup_p1 EXIT
trap 'exit 1' HUP INT TERM

docker exec "$p1_container" sh -c 'exec createdb -U "$POSTGRES_USER" "$1"' sh "$p1_validation_db"
p1_db_created=true

psql_p1() {
  docker exec -i "$p1_container" sh -c 'exec psql -U "$POSTGRES_USER" -d "$1" -v ON_ERROR_STOP=1' sh "$p1_validation_db"
}

psql_p1 < resources/database/schema_pg.sql
psql_p1 < resources/database/init_data_pg.sql

psql_p1 <<'SQL'
DO $$
BEGIN
    IF to_regclass('t_iron_ore_task_template') IS NULL THEN
        RAISE EXCEPTION 'Temporary draft table missing';
    END IF;
    IF to_regclass('t_task_agent_run') IS NOT NULL
       OR to_regclass('t_task_agent_event') IS NOT NULL
       OR to_regclass('t_task_agent_sample') IS NOT NULL
       OR to_regclass('t_task_agent_station') IS NOT NULL
       OR to_regclass('t_task_agent_submission') IS NOT NULL
       OR to_regclass('t_iron_ore_task_execution') IS NOT NULL
       OR to_regclass('t_iron_ore_robot_mission') IS NOT NULL THEN
        RAISE EXCEPTION 'Retired table in fresh schema';
    END IF;
END $$;

INSERT INTO t_iron_ore_task_template
    (id, conversation_id, source_message_id, doc_id, owner_user_id, title, status, template_data)
VALUES ('p1-draft', 'p1-conversation', 'p1-answer', 'p1-doc', 'p1-owner', 'Draft check', 'DRAFT',
        '{"steps":[{"order":1,"action":"Read supplied source"}]}'::jsonb);
UPDATE t_iron_ore_task_template SET status = 'APPROVED' WHERE id = 'p1-draft';
DO $$
BEGIN
    IF (SELECT status FROM t_iron_ore_task_template WHERE id = 'p1-draft') IS DISTINCT FROM 'APPROVED'
       OR (SELECT template_data->'steps'->0->>'action' FROM t_iron_ore_task_template WHERE id = 'p1-draft')
          IS DISTINCT FROM 'Read supplied source' THEN
        RAISE EXCEPTION 'Draft CRUD failed';
    END IF;
END $$;

-- Represent an existing environment with historical tables and sentinel records.
CREATE TABLE t_task_agent_run (id TEXT PRIMARY KEY);
CREATE TABLE t_iron_ore_task_execution (id TEXT PRIMARY KEY);
CREATE TABLE t_iron_ore_robot_mission (id TEXT PRIMARY KEY);
INSERT INTO t_task_agent_run VALUES ('retained');
INSERT INTO t_iron_ore_task_execution VALUES ('retained');
INSERT INTO t_iron_ore_robot_mission VALUES ('retained');
INSERT INTO t_intent_node (id, intent_code, name, level, kind, mcp_tool_id, enabled)
VALUES ('p1-retired', 'p1-retired', 'Simulation', 0, 2, 'iron_ore_simulate_task', 1),
       ('p1-preserved', 'p1-preserved', 'Version diff', 0, 2, 'iron_ore_compare_versions', 1);
SQL

psql_p1 < resources/database/upgrades/v1.1.0/260917_retire_execution_demo.sql
psql_p1 < resources/database/upgrades/v1.1.0/260917_retire_execution_demo.sql

psql_p1 <<'SQL'
DO $$
BEGIN
    IF (SELECT enabled FROM t_intent_node WHERE id = 'p1-retired') IS DISTINCT FROM 0
       OR (SELECT enabled FROM t_intent_node WHERE id = 'p1-preserved') IS DISTINCT FROM 1 THEN
        RAISE EXCEPTION 'Retirement changed wrong intent';
    END IF;
    IF (SELECT count(*) FROM t_task_agent_run WHERE id = 'retained') <> 1
       OR (SELECT count(*) FROM t_iron_ore_task_execution WHERE id = 'retained') <> 1
       OR (SELECT count(*) FROM t_iron_ore_robot_mission WHERE id = 'retained') <> 1
       OR (SELECT count(*) FROM t_iron_ore_task_template WHERE id = 'p1-draft') <> 1 THEN
        RAISE EXCEPTION 'Historical or draft data was removed';
    END IF;
END $$;
SELECT 'PASS: fresh schema, draft CRUD, scoped/idempotent retirement, retained historical rows' AS validation;
SQL
