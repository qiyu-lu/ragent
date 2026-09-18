#!/usr/bin/env bash
set -euo pipefail

# Uses a running pgvector/PostgreSQL dev container. All mutations target a random temporary database.
p2_repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd -- "$p2_repo_root"
p2_container=${P2_POSTGRES_CONTAINER:-ragent-iron-ore-dev-postgres-1}
p2_validation_db="research_p2_$(date -u +%Y%m%d%H%M%S)_${RANDOM}"
p2_db_created=false
p2_scratch=$(mktemp -d /tmp/agentic-research-p2-sql.XXXXXX)

cleanup_p2() {
  if [ "$p2_db_created" = true ]; then
    docker exec "$p2_container" sh -c 'exec dropdb -U "$POSTGRES_USER" "$1"' sh "$p2_validation_db"
    echo 'Temporary P2 validation database removed.'
  fi
  rm -rf -- "$p2_scratch"
}
trap cleanup_p2 EXIT
trap 'exit 1' HUP INT TERM

docker exec "$p2_container" sh -c 'exec createdb -U "$POSTGRES_USER" "$1"' sh "$p2_validation_db"
p2_db_created=true

psql_p2() {
  docker exec -i "$p2_container" sh -c '
    p2_db=$1
    shift
    exec psql -U "$POSTGRES_USER" -d "$p2_db" -v ON_ERROR_STOP=1 "$@"
  ' sh "$p2_validation_db" "$@"
}

psql_p2 < resources/database/schema_pg.sql
psql_p2 < resources/database/init_data_pg.sql

p2_catalog_sql="SELECT table_name, column_name, udt_name, is_nullable, column_default
  FROM information_schema.columns
  WHERE table_schema = 'public' AND table_name IN ('t_research_run', 't_research_evidence', 't_research_event', 't_research_corpus_document', 't_knowledge_base', 't_knowledge_base_grant')
  ORDER BY table_name, ordinal_position;
  SELECT conrelid::regclass, conname, pg_get_constraintdef(oid)
  FROM pg_constraint
  WHERE conrelid IN ('t_research_run'::regclass, 't_research_evidence'::regclass, 't_research_event'::regclass, 't_research_corpus_document'::regclass, 't_knowledge_base'::regclass, 't_knowledge_base_grant'::regclass)
  ORDER BY conrelid::regclass::text, conname;
  SELECT tablename, indexname, indexdef FROM pg_indexes
  WHERE schemaname = 'public' AND tablename IN ('t_research_run', 't_research_evidence', 't_research_event', 't_research_corpus_document', 't_knowledge_vector', 't_knowledge_base', 't_knowledge_base_grant')
  ORDER BY tablename, indexname;"
psql_p2 -Atc "$p2_catalog_sql" > "$p2_scratch/fresh-catalog.txt"

# Recreate the historical schema only inside this random database. The current
# fresh schema intentionally omits retired task tables; existing deployments
# still keep them, so the upgrade must preserve a real historical row.
psql_p2 < resources/database/upgrades/v1.1.0/260812_iron_ore_demo.sql

psql_p2 <<'SQL'
INSERT INTO t_iron_ore_task_template
    (id, conversation_id, source_message_id, doc_id, owner_user_id, title, status, template_data)
VALUES ('p2-history', 'conversation', 'answer', 'document', 'owner', 'History sentinel', 'DRAFT', '{}'::jsonb);

-- Only the research tables in this newly created isolated database are removed.
DROP TABLE t_research_event;
DROP TABLE t_research_evidence;
DROP TABLE t_research_run;
DROP TABLE t_research_corpus_document;
DROP INDEX idx_vector_collection_doc;

-- W4: rewind the knowledge-base access columns and keep a pre-existing row created by an existing user.
DROP TABLE t_knowledge_base_grant;
ALTER TABLE t_knowledge_base DROP CONSTRAINT ck_kb_visibility;
ALTER TABLE t_knowledge_base DROP COLUMN visibility;
ALTER TABLE t_knowledge_base DROP COLUMN owner_user_id;
INSERT INTO t_knowledge_base (id, name, embedding_model, collection_name, created_by)
SELECT 'p2-legacy-kb', 'legacy', 'fixture', 'p2_legacy', username FROM t_user ORDER BY id LIMIT 1;
SQL
psql_p2 < resources/database/upgrades/v1.1.0/260917_02_research_evidence.sql
psql_p2 < resources/database/upgrades/v1.1.0/260917_03_research_neighbors.sql
psql_p2 < resources/database/upgrades/v1.1.0/260917_04_research_corpus.sql
psql_p2 < resources/database/upgrades/v1.1.0/260918_01_research_durable_execution.sql
psql_p2 < resources/database/upgrades/v1.1.0/260917_02_research_evidence.sql
psql_p2 < resources/database/upgrades/v1.1.0/260917_03_research_neighbors.sql
psql_p2 < resources/database/upgrades/v1.1.0/260917_04_research_corpus.sql
psql_p2 < resources/database/upgrades/v1.1.0/260918_01_research_durable_execution.sql
psql_p2 < resources/database/upgrades/v1.1.0/260918_02_knowledge_base_access.sql
psql_p2 < resources/database/upgrades/v1.1.0/260918_02_knowledge_base_access.sql
psql_p2 -Atc "$p2_catalog_sql" > "$p2_scratch/upgraded-catalog.txt"
diff -u "$p2_scratch/fresh-catalog.txt" "$p2_scratch/upgraded-catalog.txt"

psql_p2 <<'SQL'
INSERT INTO t_research_run (id, owner_user_id, conversation_id, client_request_id, output_type, brief)
VALUES ('p2-run-a', 'owner-a', 'conversation', 'request', 'REPORT',
        '{"goal":"compare","outputType":"REPORT","constraints":[],"allowedKbIds":["kb-a"]}'),
       ('p2-run-b', 'owner-b', 'conversation', 'request', 'PLAN',
        '{"goal":"prepare","outputType":"PLAN","constraints":[],"allowedKbIds":["kb-a"]}');

-- The same INSERT SELECT / ON CONFLICT storage primitive as the Java evidence store.
INSERT INTO t_research_evidence
    (evidence_id, run_id, kb_id, doc_id, document_name, document_version, chunk_ids,
     content_hash, source_text, text, source_location, source_metadata_hash,
     retrieved_by_task_id, truncated, read, source_extent)
SELECT 'ev-p2-check', id, 'kb-a', 'doc-a', 'paper.md', 'V1', '["chunk-a"]'::jsonb,
       repeat('a',64), 'source body', 'source', '{"source_paragraph_id":"p-7"}'::jsonb,
       repeat('b',64), 'worker-a', TRUE, FALSE, 'AVAILABLE_EXCERPT'
FROM t_research_run WHERE id = 'p2-run-a' AND owner_user_id = 'owner-a'
ON CONFLICT (evidence_id) DO NOTHING;

INSERT INTO t_research_evidence
    (evidence_id, run_id, kb_id, doc_id, document_name, document_version, chunk_ids,
     content_hash, source_text, text, source_location, source_metadata_hash,
     retrieved_by_task_id, truncated, read, source_extent)
SELECT 'ev-p2-check', id, 'kb-a', 'doc-a', 'paper.md', 'V2', '["chunk-a"]'::jsonb,
       repeat('c',64), 'replacement body', 'replacement', '{}'::jsonb,
       repeat('d',64), 'worker-b', FALSE, FALSE, 'CHUNK'
FROM t_research_run WHERE id = 'p2-run-a' AND owner_user_id = 'owner-a'
ON CONFLICT (evidence_id) DO NOTHING;

-- Wrong-owner insertion has no selected run; it must insert zero rows.
INSERT INTO t_research_evidence
    (evidence_id, run_id, kb_id, doc_id, document_name, chunk_ids,
     content_hash, source_text, text, source_metadata_hash,
     retrieved_by_task_id, truncated, source_extent)
SELECT 'ev-p2-foreign', id, 'kb-a', 'doc-a', 'paper.md', '["chunk-a"]'::jsonb,
       repeat('a',64), 'body', 'body', repeat('b',64), 'worker', FALSE, 'CHUNK'
FROM t_research_run WHERE id = 'p2-run-a' AND owner_user_id = 'owner-b'
ON CONFLICT (evidence_id) DO NOTHING;

UPDATE t_research_evidence e
SET text = source_text, truncated = FALSE, read = TRUE, update_time = CURRENT_TIMESTAMP
WHERE e.evidence_id = 'ev-p2-check' AND e.run_id = 'p2-run-a'
  AND EXISTS (SELECT 1 FROM t_research_run r WHERE r.id = e.run_id AND r.owner_user_id = 'owner-a');

WITH allocated AS (
    UPDATE t_research_run SET event_sequence = event_sequence + 1 WHERE id = 'p2-run-a'
    RETURNING id, event_sequence
)
INSERT INTO t_research_event (run_id, sequence_no, task_id, event_type, summary)
SELECT id, event_sequence, 'main', 'SOURCE_READ', 'Storage example' FROM allocated;

INSERT INTO t_knowledge_base (id, name, embedding_model, collection_name, created_by)
VALUES ('p2-new-kb', 'new', 'fixture', 'p2_new', 'nobody');

DO $$
BEGIN
    IF (SELECT visibility FROM t_knowledge_base WHERE id = 'p2-legacy-kb') <> 'PUBLIC'
       OR (SELECT owner_user_id FROM t_knowledge_base WHERE id = 'p2-legacy-kb') IS DISTINCT FROM (SELECT id FROM t_user ORDER BY id LIMIT 1)
       OR (SELECT visibility FROM t_knowledge_base WHERE id = 'p2-new-kb') <> 'PRIVATE' THEN
        RAISE EXCEPTION 'Knowledge-base access upgrade must keep existing rows PUBLIC with owners and default new rows to PRIVATE';
    END IF;
    IF (SELECT count(*) FROM t_iron_ore_task_template WHERE id = 'p2-history' AND status = 'DRAFT') <> 1 THEN
        RAISE EXCEPTION 'Incremental upgrade changed historical draft';
    END IF;
    IF (SELECT count(*) FROM t_research_evidence) <> 1
       OR (SELECT source_text FROM t_research_evidence WHERE evidence_id = 'ev-p2-check') <> 'source body'
       OR (SELECT document_version FROM t_research_evidence WHERE evidence_id = 'ev-p2-check') <> 'V1'
       OR (SELECT retrieved_by_task_id FROM t_research_evidence WHERE evidence_id = 'ev-p2-check') <> 'worker-a'
       OR NOT (SELECT read FROM t_research_evidence WHERE evidence_id = 'ev-p2-check') THEN
        RAISE EXCEPTION 'Snapshot upsert / delivered read primitive failed';
    END IF;
    IF EXISTS (
        SELECT 1 FROM t_research_evidence e JOIN t_research_run r ON r.id = e.run_id
        WHERE e.run_id = 'p2-run-a' AND r.owner_user_id = 'owner-b'
    ) THEN
        RAISE EXCEPTION 'Foreign owner can query evidence';
    END IF;

    BEGIN
        INSERT INTO t_research_run (id, owner_user_id, conversation_id, client_request_id, output_type, brief)
        VALUES ('p2-duplicate', 'owner-a', 'conversation', 'request', 'REPORT', '{}'::jsonb);
        RAISE EXCEPTION 'Same-owner request unique constraint missing';
    EXCEPTION WHEN unique_violation THEN NULL;
    END;
    BEGIN
        INSERT INTO t_research_event (run_id, sequence_no, task_id, event_type, summary)
        VALUES ('p2-run-a', 1, 'worker', 'SOURCE_READ', 'Duplicate');
        RAISE EXCEPTION 'Run event sequence unique constraint missing';
    EXCEPTION WHEN unique_violation THEN NULL;
    END;
    BEGIN
        UPDATE t_research_run SET status = 'FICTIONAL' WHERE id = 'p2-run-a';
        RAISE EXCEPTION 'Run status constraint missing';
    EXCEPTION WHEN check_violation THEN NULL;
    END;
    BEGIN
        INSERT INTO t_research_event (run_id, sequence_no, task_id, event_type, summary)
        VALUES ('missing-run', 1, 'main', 'SOURCE_READ', 'Orphan');
        RAISE EXCEPTION 'Event run foreign key missing';
    EXCEPTION WHEN foreign_key_violation THEN NULL;
    END;
END $$;
SQL
if [ "${P2_RUN_JAVA_TESTS:-false}" = true ]; then
  p2_pg_port=$(docker inspect --format '{{(index (index .NetworkSettings.Ports "5432/tcp") 0).HostPort}}' "$p2_container")
  p2_pg_user=$(docker exec "$p2_container" sh -c 'printf "%s" "$POSTGRES_USER"')
  p2_pg_password=$(docker exec "$p2_container" sh -c 'printf "%s" "$POSTGRES_PASSWORD"')
  RESEARCH_TEST_PG_URL="jdbc:postgresql://127.0.0.1:${p2_pg_port}/${p2_validation_db}" \
    RESEARCH_TEST_PG_USER="$p2_pg_user" RESEARCH_TEST_PG_PASSWORD="$p2_pg_password" \
    ./mvnw -o -pl bootstrap -am -Dtest=ResearchEvidencePostgresIT -Dsurefire.failIfNoSpecifiedTests=false test
  unset p2_pg_password
fi
echo 'P2 fresh schema, repeated incremental upgrade, ownership and storage checks passed.'
