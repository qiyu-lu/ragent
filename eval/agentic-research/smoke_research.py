#!/usr/bin/env python3
"""Run bounded paid research probes with real SDK tools; corpus reads and run writes use separate databases."""
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
    parser.add_argument('--phase', choices=('p3', 'p4'), default='p3')
    parser.add_argument('--case', action='append', choices=('lookup', 'multi-hop', 'insufficient-source', 'waiting-and-resume', 'cancel-in-flight', 'comparison-workers', 'plan-workers', 'cancel-workers', 'follow-up-workers'))
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
    if args.phase == 'p4':
        with (args.prepared / 'qasper-validation/queries.jsonl').open() as source:
            other = next(json.loads(line) for line in source if json.loads(line)['document_ids'] != qasper['document_ids'])
        comparison = ('Compare the research approaches in Paper A (document ID [[DOC_0]]) and Paper B (document ID [[DOC_1]]). '
                      'Use conduct_research once with two independent workers, one narrowed to each exact document ID. '
                      'Each worker should investigate the learning/model approach, supervision or inputs, and reported limitations. '
                      'Read sources and perform a targeted follow-up search if a dimension remains unresolved. '
                      'Return concise cited findings, preserve numbers, units and applicable conditions; missing details stay gaps.')
        cases = [
            {'id': 'comparison-workers', 'collection': 'rs_qasper_validation_v1_full',
             'sourceDocumentIds': qasper['document_ids'] + other['document_ids'], 'goal': comparison,
             'outputType': 'REPORT', 'cancelAfterMillis': 0, 'reply': None},
            {'id': 'plan-workers', 'collection': 'rs_qasper_validation_v1_full', 'sourceDocumentIds': qasper['document_ids'],
             'goal': 'Research an active-learning experiment plan from this paper with a maximum of 500 manually annotated training examples. '
                     'Use conduct_research once with two independent tasks: (1) source-supported prerequisites, data and resources; '
                     '(2) source-supported learning/annotation loop, evaluation steps and limitations. '
                     'Workers should search, read and target a follow-up query when needed. This is a plan draft: preserve parameters and conditions, '
                     'leave unspecified deployment or equipment values as gaps. Integrate only compressed cited results.',
             'outputType': 'PLAN', 'cancelAfterMillis': 0, 'reply': None},
            next(case for case in cases if case['id'] == 'multi-hop'),
            {'id': 'cancel-workers', 'collection': 'rs_qasper_validation_v1_full',
             'sourceDocumentIds': qasper['document_ids'] + other['document_ids'], 'goal': comparison,
             'outputType': 'REPORT', 'cancelAfterMillis': 60000, 'cancelWhenWorkersRunning': True, 'reply': None},
        ]
    if args.phase == 'p4' and args.case and 'follow-up-workers' in args.case:
        cases.append({'id': 'follow-up-workers', 'collection': 'rs_qasper_validation_v1_full', 'sourceDocumentIds': qasper['document_ids'],
                      'goal': 'Use conduct_research once with exactly two independent workers on this paper. '
                              'Worker A investigates the learning model and batch selection parameters: first search only for the model/classifier, '
                              'limit 1, then read it; identify a model/classifier name from that source and issue a NEW search using that exact name '
                              'to investigate selection or batch parameters, limit 1, then read and finish. '
                              'Worker B investigates the stopping criterion and its window/threshold parameters: first search only for the stopping rule, '
                              'limit 1, then read it; identify a criterion or method name from that source and issue a NEW search using that exact name '
                              'to investigate its window/threshold details, limit 1, then read and finish. '
                              'Put these execution requirements into the delegated task goals. A source-derived follow-up query after the first read is required '
                              'for each worker even if the first excerpt contains partial parameter details. Preserve exact evidence IDs, numbers and units; '
                              'missing parameters remain gaps. Return only compressed cited findings.',
                      'outputType': 'REPORT', 'cancelAfterMillis': 0, 'reply': None})
    if args.case:
        cases = [case for case in cases if case['id'] in args.case]
    if not cases:
        raise ValueError('The selected case does not belong to the selected phase')
    args.run_dir.mkdir(parents=True, exist_ok=False)
    job = {'runDir': str(args.run_dir.resolve()), 'cases': cases}
    (args.run_dir / 'job.json').write_text(json.dumps(job, indent=2) + '\n')
    record = {'started_at': datetime.now(timezone.utc).isoformat(), 'mode': 'P4-worker-smoke' if args.phase == 'p4' else 'serial-research-smoke',
              'model_config_id': 'research-flash', 'prompt_version': 'research-main-v3', 'worker_prompt_version': 'research-worker-v2',
              'git_commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=REPO, text=True).strip(),
              'working_tree_modified': bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=REPO, text=True)),
              'query_ids': ([qasper['id']] if any(c['id'] not in ('multi-hop', 'cancel-in-flight') for c in cases) else [])
                  + ([musique['id']] if any(c['id'] in ('multi-hop', 'cancel-in-flight') for c in cases) else [])
                  + ([other['id']] if any(c['id'] in ('comparison-workers', 'cancel-workers') for c in cases) else []), 'gold_used': False,
              'query_file_sha256': {scope: digest(args.prepared / scope / 'queries.jsonl') for scope in ('qasper-validation', 'musique-dev')},
              'source_sha256': {str(p.relative_to(REPO)): digest(p) for p in sorted((REPO / 'bootstrap/src/main/java/com/nageoffer/ai/ragent/research').rglob('*.java'))},
              'config_sha256': digest(REPO / 'bootstrap/src/main/resources/application.yaml'),
              'prompt_sha256': digest(REPO / 'bootstrap/src/main/resources/prompts/research-main-v3.txt'),
              'worker_prompt_sha256': digest(REPO / 'bootstrap/src/main/resources/prompts/research-worker-v2.txt'),
              'harness_sha256': digest(Path(__file__)), 'command': sys.argv, 'paid_generation': bool(args.execute), 'scoring': False}
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
        worker_summaries = {}
        for prediction in predictions:
            tasks = prediction['state'].get('subtasks', {})
            by_task = {}
            for task_id, task in tasks.items():
                events = [t['event'] for t in traces if t['runId'] == prediction['id'] and t['event']['taskId'] == task_id]
                read, follow = False, False
                for event in events:
                    if event['type'] == 'TOOL_ENDED' and event['payload'].get('tool') == 'read_source': read = True
                    if read and event['type'] == 'TOOL_STARTED' and event['payload'].get('tool') == 'search_knowledge': follow = True
                by_task[task_id] = {'status': task['status'], 'read_evidence_count': len(task.get('readEvidenceIds', [])), 'follow_up_after_read': follow}
            worker_summaries[prediction['clientRequestId']] = by_task
        summary = {'workers': worker_summaries, 'states': {p['clientRequestId']: p['status'] for p in predictions}, 'model_requests': len(calls),
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
