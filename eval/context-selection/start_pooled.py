#!/usr/bin/env python3
"""Start the isolated profile using only the two explicitly scoped IDEA API keys."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET


def idea_environment(path: Path, configuration: str) -> dict[str, str]:
    matches = [node for node in ET.parse(path).iter('configuration') if node.get('name') == configuration]
    if len(matches) != 1:
        raise ValueError('expected exactly one IDEA run configuration')
    values = {e.get('name'): e.get('value', '') for e in matches[0].iter('env')}
    names = ('BAILIAN_API_KEY', 'SILICONFLOW_API_KEY')
    if any(not values.get(name) for name in names):
        raise ValueError('IDEA configuration is missing required API key values')
    return {name: values[name] for name in names}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--idea', type=Path, default=Path('.idea/workspace.xml'))
    parser.add_argument('--configuration', default='RagentApplication')
    parser.add_argument('--check-only', action='store_true')
    parser.add_argument('--config', type=Path, default=Path('eval/context-selection/application-pooled-eval.yaml'))
    args = parser.parse_args()
    env = dict(os.environ)
    env.update(idea_environment(args.idea, args.configuration))
    if args.check_only:
        print('Required IDEA credentials are available (values not displayed).')
        return
    repo = Path(__file__).resolve().parents[2]
    result = subprocess.run(['./mvnw', '-o', '-pl', 'bootstrap', '-Dspotless.apply.skip=true',
        'dependency:build-classpath', '-Dmdep.outputAbsoluteArtifactFilename=true'], cwd=repo,
        text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=True)
    lines = [s.strip() for s in result.stdout.splitlines() if s.startswith('/') and '.jar' in s]
    if len(lines) != 1:
        raise RuntimeError('classpath resolution failed')
    cp = os.pathsep.join([str(repo / p / 'target/classes') for p in ('bootstrap', 'framework', 'infra-ai')] + lines)
    command = ['java', '-Xmx2g', '-cp', cp, 'com.nageoffer.ai.ragent.RagentApplication',
        '--spring.profiles.active=pooled-eval',
        '--spring.config.additional-location=file:' + str((repo / args.config).resolve())]
    os.execvpe(command[0], command, env)


if __name__ == '__main__':
    main()
