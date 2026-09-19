#!/usr/bin/env python3
"""Group verified gold-free sources, then run the real Java chunk/embed/index and source tools."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone

from datasetkit import canonical, sha256_file
from verify_prepared import validate_dataset

REPO = Path(__file__).resolve().parents[2]
from sourcekit import idea_environment


def prepare_job(prepared: Path, output: Path, profile: str, dimension: int, batch: int, retries: int) -> dict:
    validate_dataset(prepared)
    manifest = json.loads((prepared / 'manifest.json').read_text())
    scope = manifest['dataset'] + '-' + manifest['split']
    output.mkdir(parents=True, exist_ok=True)
    queries = prepared / ('queries.smoke.jsonl' if profile == 'smoke' else 'queries.jsonl')
    selected = set()
    unrestricted = False
    if profile == 'smoke':
        for line in queries.open():
            ids = json.loads(line)['document_ids']
            selected.update(ids)
            unrestricted |= not ids
    documents_file = output / 'documents.jsonl'
    previous = output / 'input.json'
    identity = {'prepared_manifest_sha256': sha256_file(prepared / 'manifest.json'), 'profile': profile,
                'dimension': dimension, 'max_chars': 1024, 'overlap_chars': 128}
    if previous.exists():
        saved = json.loads(previous.read_text())
        if saved['identity'] != identity or sha256_file(documents_file) != saved['documents_sha256']:
            raise ValueError('import input changed; use a new run directory')
        counts = saved['counts']
    else:
        with tempfile.TemporaryDirectory(prefix='research-import-') as scratch:
            with sqlite3.connect(str(Path(scratch) / 'order.sqlite')) as db:
                db.execute('CREATE TABLE units (doc TEXT, field_order INT, section INT, paragraph INT, payload TEXT)')
                for line in (prepared / 'corpus.jsonl').open():
                    unit = json.loads(line)
                    if profile == 'smoke' and not unrestricted and unit['document_id'] not in selected:
                        continue
                    meta = unit['metadata']
                    db.execute('INSERT INTO units VALUES (?,?,?,?,?)', (unit['document_id'],
                               0 if meta['source_field'] == 'abstract' else 1, meta.get('section_index', -1),
                               meta.get('paragraph_index', 0), canonical(unit)))
                counts = {'source_units': db.execute('SELECT count(*) FROM units').fetchone()[0],
                          'documents': db.execute('SELECT count(DISTINCT doc) FROM units').fetchone()[0]}
                with documents_file.open('w') as out:
                    document_id, units = None, []
                    for identifier, payload in db.execute('SELECT doc,payload FROM units ORDER BY doc,field_order,section,paragraph'):
                        if document_id is not None and identifier != document_id:
                            out.write(canonical({'sourceDocumentId': document_id, 'units': units}) + '\n')
                            units = []
                        document_id = identifier
                        units.append(json.loads(payload))
                    if units:
                        out.write(canonical({'sourceDocumentId': document_id, 'units': units}) + '\n')
        previous.write_text(json.dumps({'identity': identity, 'counts': counts,
                            'documents_sha256': sha256_file(documents_file)}, indent=2) + '\n')
    job = {'documentsFile': str(documents_file.resolve()), 'queriesFile': str(queries.resolve()),
           'collection': 'rs_' + scope.replace('-', '_') + '_v1_' + profile,
           'runDir': str(output.resolve()), 'batchDocuments': batch or (256 if manifest['dataset'] == 'musique' else 8), 'maxRetries': retries,
           'maxChars': 1024, 'overlapChars': 128, 'dimension': dimension}
    (output / 'job.json').write_text(json.dumps(job, indent=2) + '\n')
    return {'scope': scope, 'job': job, 'counts': counts}


def database_environment(container: str, database: str, dimension: int) -> dict:
    if not database.startswith('research_corpus_') or not database.replace('_', '').isalnum():
        raise ValueError('dedicated research_corpus_ database required')
    def docker(*args, **kwargs):
        return subprocess.run(['docker', 'exec', '-i', container, *args], check=True,
                              text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, **kwargs).stdout
    exists = docker('sh', '-c', 'exec psql -U "$POSTGRES_USER" -d postgres -Atc "$1"', 'sh',
                    "SELECT count(*) FROM pg_database WHERE datname='" + database + "'").strip() == '1'
    if not exists:
        docker('sh', '-c', 'exec createdb -U "$POSTGRES_USER" "$1"', 'sh', database)
        docker('sh', '-c', 'exec psql -U "$POSTGRES_USER" -d "$1" -v ON_ERROR_STOP=1', 'sh', database,
               input=(REPO / 'resources/database/schema_pg.sql').read_text())
        if dimension != 1536:
            docker('sh', '-c', 'exec psql -U "$POSTGRES_USER" -d "$1" -v ON_ERROR_STOP=1', 'sh', database,
                   input=f'ALTER TABLE t_knowledge_vector ALTER COLUMN embedding TYPE vector({dimension});')
    # Dimension/config drift is caught before an embedding call.
    actual = docker('sh', '-c', 'exec psql -U "$POSTGRES_USER" -d "$1" -Atc "$2"', 'sh', database,
                    "SELECT format_type(atttypid,atttypmod) FROM pg_attribute WHERE attrelid='t_knowledge_vector'::regclass AND attname='embedding'").strip()
    if actual != f'vector({dimension})':
        raise ValueError('database vector dimension differs from job')
    port = subprocess.check_output(['docker', 'inspect', '--format', '{{(index (index .NetworkSettings.Ports "5432/tcp") 0).HostPort}}', container], text=True).strip()
    return {'RAGENT_POSTGRES_URL': f'jdbc:postgresql://127.0.0.1:{port}/{database}',
            'RAGENT_POSTGRES_USER': docker('sh', '-c', 'printf "%s" "$POSTGRES_USER"'),
            'RAGENT_POSTGRES_PASSWORD': docker('sh', '-c', 'printf "%s" "$POSTGRES_PASSWORD"')}


def summarize(run: Path, jobs: list, database: str) -> dict:
    result = {'database': database, 'splits': [], 'quality_evaluation': False}
    for item in jobs:
        directory = Path(item['job']['runDir'])
        complete = json.loads((directory / 'complete.json').read_text())
        requests = {}
        for index, line in enumerate((directory / 'usage.jsonl').open()):
            event = json.loads(line)
            requests[event.get('call_id', 'legacy-' + str(index))] = event
        usage = list(requests.values())
        provider_tokens = sum(row.get('usage', {}).get('total_tokens', 0) for row in usage if row.get('usage') is not None)
        result['splits'].append({**complete, 'scope': item['scope'], 'expected': item['counts'],
            'actual_requests': len(usage), 'failed_requests': sum(not row['success'] and row.get('request_state') != 'STARTED' for row in usage),
            'incomplete_requests': sum(row.get('request_state') == 'STARTED' for row in usage),
            'provider_total_tokens': provider_tokens, 'unknown_usage_requests': sum(row['usage_status'] == 'unknown' for row in usage),
            'files': {name: sha256_file(directory / name) for name in
                      ('input.json', 'documents.jsonl', 'mapping.jsonl', 'source-probes.jsonl', 'usage.jsonl', 'traces.jsonl', 'complete.json')}})
    (run / 'summary.json').write_text(json.dumps(result, indent=2) + '\n')
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--prepared', type=Path, action='append', required=True)
    parser.add_argument('--run-dir', type=Path, required=True)
    parser.add_argument('--profile', choices=('smoke', 'full'), default='smoke')
    parser.add_argument('--dimension', type=int, default=1536)
    parser.add_argument('--batch-documents', type=int)
    parser.add_argument('--max-retries', type=int, default=3)
    parser.add_argument('--workers', type=int, default=4)
    parser.add_argument('--database', default='research_corpus_v1')
    parser.add_argument('--container', default='ragent-iron-ore-dev-postgres-1')
    parser.add_argument('--idea', type=Path, default=Path('.idea/workspace.xml'))
    parser.add_argument('--execute', action='store_true')
    args = parser.parse_args()
    jobs = [prepare_job(path, args.run_dir / path.name, args.profile, args.dimension,
                        args.batch_documents, args.max_retries) for path in args.prepared]
    print(json.dumps([{'scope': item['scope'], **item['counts']} for item in jobs]), flush=True)
    if not args.execute:
        return
    env = dict(os.environ)
    if not env.get('SILICONFLOW_API_KEY'):
        env.update(idea_environment(args.idea, 'RagentApplication'))
    env.update(database_environment(args.container, args.database, args.dimension))
    resolved = subprocess.run(['./mvnw', '-o', '-pl', 'bootstrap', '-Dspotless.apply.skip=true',
                              'dependency:build-classpath', '-Dmdep.outputAbsoluteArtifactFilename=true'],
                             cwd=REPO, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=True)
    lines = [line.strip() for line in resolved.stdout.splitlines() if line.startswith('/') and '.jar' in line]
    if len(lines) != 1:
        raise RuntimeError('classpath resolution failed')
    classpath = os.pathsep.join([str(REPO / module / 'target/classes') for module in ('bootstrap', 'framework', 'infra-ai')] + lines)
    invocation = {'started_at': datetime.now(timezone.utc).isoformat(),
        'git_commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
        'git_dirty': bool(subprocess.check_output(['git', 'status', '--porcelain'], text=True)),
        'command': sys.argv, 'profile': args.profile, 'database': args.database, 'jobs': jobs,
        'embedding_requests_per_split': 16,
        'source_hashes': {str(path.relative_to(REPO)): sha256_file(path) for path in
                         list((REPO / 'bootstrap/src/main/java/com/nageoffer/ai/ragent/research').rglob('*.java'))
                         + list((REPO / 'infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/embedding').glob('*.java'))
                         + [Path(__file__).resolve(), REPO / 'resources/database/schema_pg.sql']}}
    if not (args.run_dir / 'run.json').exists():
        (args.run_dir / 'run.json').write_text(json.dumps(invocation, indent=2) + '\n')
    with (args.run_dir / 'invocations.jsonl').open('a') as history:
        history.write(canonical(invocation) + '\n')
    def execute(item):
        directory = Path(item['job']['runDir'])
        with (directory / 'java.log').open('a') as log:
            completed = subprocess.run(['java', '-Xmx1g', '-cp', classpath,
                'com.nageoffer.ai.ragent.research.eval.ResearchCorpusCommand', str(directory / 'job.json')],
                cwd=REPO, env=env, stdout=log, stderr=subprocess.STDOUT)
        if completed.returncode:
            raise RuntimeError('import failed: ' + item['scope'] + '; see ' + str(directory / 'java.log'))
        print('Completed ' + item['scope'], flush=True)
    with ThreadPoolExecutor(max_workers=args.workers) as workers:
        futures = [workers.submit(execute, item) for item in jobs]
        failures = []
        for future in futures:
            try:
                future.result()
            except Exception as error:
                failures.append(str(error))
        if failures:
            raise RuntimeError('\n'.join(failures))
    print(json.dumps(summarize(args.run_dir, jobs, args.database), indent=2))


if __name__ == '__main__':
    main()
