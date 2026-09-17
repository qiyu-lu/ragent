#!/usr/bin/env python3
"""Run five paid P3 probes with real SDK tools; corpus reads and run writes use separate databases."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

REPO = Path(__file__).resolve().parents[2]
sys.path.append(str(REPO / 'eval/context-selection'))
from start_pooled import idea_environment


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--prepared', type=Path, default=Path('local-data/agentic-research/prepared/research-data-v1'))
    parser.add_argument('--run-dir', type=Path, required=True)
    parser.add_argument('--container', default='ragent-iron-ore-dev-postgres-1')
    parser.add_argument('--corpus-database', default='research_corpus_v1')
    parser.add_argument('--idea', type=Path, default=Path('.idea/workspace.xml'))
    parser.add_argument('--execute', action='store_true')
    parser.add_argument('--case', choices=('lookup', 'multi-hop', 'insufficient-source', 'waiting-and-resume', 'cancel-in-flight'))
    args = parser.parse_args()
    if not args.corpus_database.startswith('research_corpus_') or not args.corpus_database.replace('_', '').isalnum():
        raise ValueError('A dedicated research_corpus_ database is required')
    def query(scope, index=0):
        with (args.prepared / scope / 'queries.jsonl').open() as source:
            for _ in range(index + 1):
                item = json.loads(source.readline())
        return item
    qasper, musique = query('qasper-validation'), query('musique-dev', 1)
    cases = [
        {'id': 'lookup', 'collection': 'rs_qasper_validation_v1_full', 'sourceDocumentIds': qasper['document_ids'],
         'goal': qasper['question'], 'outputType': 'REPORT', 'cancelAfterMillis': 0, 'reply': None},
        {'id': 'multi-hop', 'collection': 'rs_musique_dev_v1_full', 'sourceDocumentIds': musique['document_ids'],
         'goal': musique['question'] + ' First read a source to identify the country where Kaimana is located. Then perform a new search using that identified country to trace the Commission of Truth and Friendship, and follow up to identify the president. Do not merely reuse the initial search for all stages.',
         'outputType': 'REPORT', 'cancelAfterMillis': 0, 'reply': None},
        {'id': 'insufficient-source', 'collection': 'rs_qasper_validation_v1_full', 'sourceDocumentIds': qasper['document_ids'],
         'goal': 'What exact GPU-hour budget and carbon emissions in kg CO2 does this paper specify for a 2035 production deployment? Report a gap if the source does not specify these values.',
         'outputType': 'REPORT', 'cancelAfterMillis': 0, 'reply': None},
        {'id': 'waiting-and-resume', 'collection': 'rs_qasper_validation_v1_full', 'sourceDocumentIds': qasper['document_ids'],
         'goal': 'Prepare a plan for an active-learning experiment based on this paper, with my maximum budget of manually annotated training examples as a hard user requirement. I have not supplied that maximum. Ask me for that number before preparing the plan.',
         'outputType': 'PLAN', 'cancelAfterMillis': 0, 'reply': 'Maximum budget: 500 manually annotated training examples.'},
        {'id': 'cancel-in-flight', 'collection': 'rs_musique_dev_v1_full', 'sourceDocumentIds': musique['document_ids'],
         'goal': musique['question'], 'outputType': 'REPORT', 'cancelAfterMillis': 800, 'reply': None},
    ]
    if args.case:
        cases = [case for case in cases if case['id'] == args.case]
    args.run_dir.mkdir(parents=True, exist_ok=False)
    job = {'runDir': str(args.run_dir.resolve()), 'cases': cases}
    (args.run_dir / 'job.json').write_text(json.dumps(job, indent=2) + '\n')
    record = {'started_at': datetime.now(timezone.utc).isoformat(), 'mode': 'P3-single-agent-smoke',
              'model_config_id': 'research-flash', 'prompt_version': 'research-main-v2',
              'git_commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=REPO, text=True).strip(),
              'working_tree_modified': bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=REPO, text=True)),
              'query_ids': [qasper['id'], musique['id']], 'gold_used': False,
              'query_file_sha256': {scope: digest(args.prepared / scope / 'queries.jsonl') for scope in ('qasper-validation', 'musique-dev')},
              'source_sha256': {str(p.relative_to(REPO)): digest(p) for p in sorted((REPO / 'bootstrap/src/main/java/com/nageoffer/ai/ragent/research').rglob('*.java'))},
              'config_sha256': digest(REPO / 'bootstrap/src/main/resources/application.yaml'),
              'prompt_sha256': digest(REPO / 'bootstrap/src/main/resources/prompts/research-main-v2.txt'),
              'command': sys.argv, 'paid_generation': bool(args.execute), 'scoring': False}
    (args.run_dir / 'run.json').write_text(json.dumps(record, indent=2) + '\n')
    if not args.execute:
        print('Prepared {} smoke probes; no API or database calls made.'.format(len(cases)))
        return
    env = dict(os.environ)
    if not env.get('BAILIAN_API_KEY') or not env.get('SILICONFLOW_API_KEY'):
        env.update(idea_environment(args.idea, 'RagentApplication'))
    def docker(*arguments, **kwargs):
        return subprocess.check_output(['docker', 'exec', '-i', args.container, *arguments], text=True, **kwargs).strip()
    database = 'research_p3_' + datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S') + '_' + os.urandom(4).hex()
    created = False
    try:
        docker('sh', '-c', 'exec createdb -U "$POSTGRES_USER" "$1"', 'sh', database)
        created = True
        docker('sh', '-c', 'exec psql -U "$POSTGRES_USER" -d "$1" -v ON_ERROR_STOP=1', 'sh', database,
               input=(REPO / 'resources/database/schema_pg.sql').read_text())
        port = subprocess.check_output(['docker', 'inspect', '--format', '{{(index (index .NetworkSettings.Ports "5432/tcp") 0).HostPort}}', args.container], text=True).strip()
        env.update({'RESEARCH_TEST_PG_USER': docker('sh', '-c', 'printf "%s" "$POSTGRES_USER"'),
                    'RESEARCH_TEST_PG_PASSWORD': docker('sh', '-c', 'printf "%s" "$POSTGRES_PASSWORD"'),
                    'RESEARCH_P3_TEST_URL': 'jdbc:postgresql://127.0.0.1:' + port + '/' + database,
                    'RAGENT_POSTGRES_URL': 'jdbc:postgresql://127.0.0.1:' + port + '/' + args.corpus_database})
        resolved = subprocess.check_output(['./mvnw', '-o', '-pl', 'bootstrap', 'dependency:build-classpath', '-Dmdep.outputAbsoluteArtifactFilename=true'], cwd=REPO, text=True)
        lines = [line.strip() for line in resolved.splitlines() if line.startswith('/') and '.jar' in line]
        if len(lines) != 1:
            raise ValueError('Classpath resolution failed')
        classpath = os.pathsep.join([str(REPO / p / 'target/classes') for p in ('bootstrap', 'framework', 'infra-ai')] + lines)
        with (args.run_dir / 'java.log').open('w') as log:
            subprocess.run(['java', '-Xmx1g', '-cp', classpath, 'com.nageoffer.ai.ragent.research.eval.ResearchRunCommand', str((args.run_dir / 'job.json').resolve())],
                           cwd=REPO, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
        predictions = [json.loads(line) for line in (args.run_dir / 'predictions.jsonl').read_text().splitlines()]
        calls = [json.loads(line)['call'] for line in (args.run_dir / 'usage.jsonl').read_text().splitlines()]
        traces = [json.loads(line) for line in (args.run_dir / 'traces.jsonl').read_text().splitlines()]
        read_seen, dependent_search = False, False
        for trace in traces:
            if trace['caseId'] != 'multi-hop':
                continue
            event = trace['event']
            if event['type'] == 'TOOL_ENDED' and event['payload'].get('tool') == 'read_source':
                read_seen = True
            if read_seen and event['type'] == 'TOOL_STARTED' and event['payload'].get('tool') == 'search_knowledge':
                dependent_search = True
        summary = {'states': {p['clientRequestId']: p['status'] for p in predictions}, 'model_requests': len(calls),
                   'provider_input_tokens': sum(c.get('inputTokens', 0) for c in calls),
                   'provider_output_tokens': sum(c.get('outputTokens', 0) for c in calls),
                   'unknown_usage_requests': sum(c['usageStatus'] == 'unknown' for c in calls),
                   'actual_models': sorted({c['model'] for c in calls}), 'dependent_search_after_read': dependent_search,
                   'waiting_input_seen': any(t['event']['type'] == 'WAITING_INPUT' for t in traces),
                   'artifacts_generated': False, 'scoring_run': False}
        (args.run_dir / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
        print(json.dumps(summary, indent=2))
    finally:
        if created:
            docker('sh', '-c', 'exec dropdb -U "$POSTGRES_USER" "$1"', 'sh', database)
        record['finished_at'] = datetime.now(timezone.utc).isoformat()
        record['isolated_database_removed'] = created
        (args.run_dir / 'run.json').write_text(json.dumps(record, indent=2) + '\n')


if __name__ == '__main__':
    main()
