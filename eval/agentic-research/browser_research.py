#!/usr/bin/env python3
"""Browser fixture smoke: real React/research HTTP/SDK/PostgreSQL, controlled model/search/auth/QA.

Requires Google Chrome and Python websocket-client already installed. Never uses provider keys.
"""
from __future__ import annotations

import argparse
import base64
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.request

import websocket

REPO = Path(__file__).resolve().parents[2]


def available_port():
    with socket.socket() as connection:
        connection.bind(('127.0.0.1', 0))
        return connection.getsockname()[1]


def json_url(url):
    with urllib.request.urlopen(url, timeout=2) as response:
        return json.load(response)


class Chrome:
    def __init__(self, url):
        self.socket = websocket.create_connection(url, timeout=30)
        self.sequence = 0
        self.requests = []

    def call(self, method, params=None):
        self.sequence += 1
        self.socket.send(json.dumps({'id': self.sequence, 'method': method, 'params': params or {}}))
        while True:
            response = json.loads(self.socket.recv())
            if response.get('method') == 'Network.requestWillBeSent':
                self.requests.append(response['params']['request'])
            if response.get('id') == self.sequence:
                if 'error' in response:
                    raise RuntimeError(response['error'])
                return response.get('result', {})

    def evaluate(self, expression):
        result = self.call('Runtime.evaluate', {'expression': expression, 'awaitPromise': True, 'returnByValue': True})
        if 'exceptionDetails' in result:
            raise RuntimeError(result['exceptionDetails'])
        return result.get('result', {}).get('value')

    def wait(self, expression, timeout=25):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self.evaluate('Boolean(document.body) && Boolean(' + expression + ')'):
                return
            time.sleep(0.15)
        raise RuntimeError('Browser assertion timed out: ' + expression + '\n' + str(self.evaluate('document.body.innerText')))

    def button(self, text):
        expression = 'Array.from(document.querySelectorAll("button")).find(b=>b.textContent.trim()===' + json.dumps(text) + ')'
        self.wait(expression + ' && !(' + expression + ').disabled')
        self.evaluate('(' + expression + ').click()')

    def submit(self, mode, goal):
        self.button(mode)
        if mode != '普通问答':
            self.wait('document.body.innerText.includes("已选 1 个知识库")')
        self.evaluate('''(() => {
          const input = document.querySelector('textarea[aria-label="聊天输入框"],textarea[aria-label="发送消息"]');
          Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set.call(input, ''' + json.dumps(goal) + ''');
          input.dispatchEvent(new Event('input',{bubbles:true}));
        })()''')
        self.wait('document.querySelector(\'button[aria-label="发送消息"]\') && !document.querySelector(\'button[aria-label="发送消息"]\').disabled')
        self.evaluate('document.querySelector(\'button[aria-label="发送消息"]\').click()')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--run-dir', type=Path, required=True)
    parser.add_argument('--container', default='ragent-iron-ore-dev-postgres-1')
    parser.add_argument('--classpath', type=Path, default=Path('/tmp/agentic-p6-classpath.txt'))
    args = parser.parse_args()
    directory = args.run_dir.resolve()
    directory.mkdir(parents=True, exist_ok=False)
    database = 'research_p3_browser_' + datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S') + '_' + os.urandom(3).hex()
    processes, logs, created, chrome = [], [], False, None
    profile = tempfile.mkdtemp(prefix='agentic-p6-chrome-')
    record = {'type': 'browser_fixture', 'started_at': datetime.now(timezone.utc).isoformat(), 'checks': [],
              'real_provider_calls': 0, 'controlled_components': ['authentication', 'knowledge search', 'source reader', 'model responses', 'ordinary QA'],
              'real_components': ['React browser', 'research controller/service', 'AgentScope SDK HTTP', 'run/evidence/event PostgreSQL'], 'scoring': False}

    def docker(*arguments, **kwargs):
        return subprocess.check_output(['docker', 'exec', '-i', args.container, *arguments], text=True, **kwargs).strip()

    def start(command, name, env, cwd=REPO):
        log = (directory / (name + '.log')).open('w'); logs.append(log)
        processes.append(subprocess.Popen(command, cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT))

    def ready(url, timeout=30):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                return json_url(url)
            except Exception:
                if any(process.poll() is not None for process in processes):
                    raise RuntimeError('Fixture process exited; inspect local logs')
                time.sleep(0.2)
        raise RuntimeError('Fixture startup timed out')

    try:
        docker('sh', '-c', 'exec createdb -U "$POSTGRES_USER" "$1"', 'sh', database); created = True
        docker('sh', '-c', 'exec psql -U "$POSTGRES_USER" -d "$1" -v ON_ERROR_STOP=1', 'sh', database, input=(REPO / 'resources/database/schema_pg.sql').read_text())
        pg_port = subprocess.check_output(['docker', 'inspect', '--format', '{{(index (index .NetworkSettings.Ports "5432/tcp") 0).HostPort}}', args.container], text=True).strip()
        backend_port, frontend_port, chrome_port = available_port(), available_port(), available_port()
        env = dict(os.environ)
        env.update({'RESEARCH_P3_TEST_URL': 'jdbc:postgresql://127.0.0.1:' + pg_port + '/' + database,
                    'RESEARCH_TEST_PG_USER': docker('sh', '-c', 'printf "%s" "$POSTGRES_USER"'),
                    'RESEARCH_TEST_PG_PASSWORD': docker('sh', '-c', 'printf "%s" "$POSTGRES_PASSWORD"'),
                    'RAGENT_VITE_PROXY_TARGET': 'http://127.0.0.1:' + str(backend_port), 'VITE_API_BASE_URL': '/api/ragent'})
        classpath = os.pathsep.join([str(REPO / p) for p in ('bootstrap/target/test-classes', 'bootstrap/target/classes', 'framework/target/classes', 'infra-ai/target/classes')] + [args.classpath.read_text().strip()])
        start(['java', '-Xmx768m', '-cp', classpath, 'com.nageoffer.ai.ragent.research.web.ResearchBrowserFixture', '--server.port=' + str(backend_port)], 'backend', env)
        backend = 'http://127.0.0.1:' + str(backend_port) + '/api/ragent'
        ready(backend + '/fixtures/summary')
        start(['node', str(REPO / 'frontend/node_modules/vite/bin/vite.js'), str(REPO / 'frontend'), '--config', str(REPO / 'frontend/vite.config.ts'), '--port', str(frontend_port), '--strictPort'], 'frontend', env, cwd=REPO / 'frontend')
        frontend = 'http://127.0.0.1:' + str(frontend_port)
        start([shutil.which('google-chrome') or 'chromium', '--headless=new', '--no-sandbox', '--disable-dev-shm-usage', '--disable-background-networking', '--no-first-run', '--remote-allow-origins=*', '--remote-debugging-port=' + str(chrome_port), '--user-data-dir=' + profile, 'about:blank'], 'chrome', env)
        targets = ready('http://127.0.0.1:' + str(chrome_port) + '/json')
        chrome = Chrome(next(t['webSocketDebuggerUrl'] for t in targets if t['type'] == 'page'))
        chrome.call('Page.enable'); chrome.call('Runtime.enable'); chrome.call('Network.enable')
        chrome.call('Emulation.setDeviceMetricsOverride', {'width': 1440, 'height': 1000, 'deviceScaleFactor': 1, 'mobile': False})
        chrome.call('Network.setBlockedURLs', {'urls': ['*api.github.com*']})
        chrome.call('Page.addScriptToEvaluateOnNewDocument', {'source': 'localStorage.setItem("ragent_token","fixture-owner");localStorage.setItem("ragent_user",JSON.stringify({userId:"fixture-owner",username:"browser fixture",role:"user",token:"fixture-owner"}));'})
        chrome.call('Page.navigate', {'url': frontend + '/chat'})
        chrome.wait('document.body.innerText.includes("普通问答")')
        chrome.submit('普通问答', 'ordinary fixture')
        chrome.wait('document.body.innerText.includes("普通问答路径可用")')
        record['checks'].append('ordinary QA mode keeps existing /rag/v3/chat request')
        chrome.submit('深入分析', 'comparison fixture')
        chrome.wait('document.body.innerText.includes("跨文档条件与操作比较")')
        chrome.wait('document.querySelectorAll("[data-source-citation]").length>=2')
        record['checks'].append('REPORT uses native workers and validated two-document citation mapping')
        chrome.submit('生成计划', 'plan fixture')
        chrome.wait('document.querySelector(\'article[aria-label="计划草稿"]\') && document.body.innerText.includes("待确认")')
        chrome.wait('document.body.innerText.includes("7 ms") && document.body.innerText.includes("温度")')
        chrome.evaluate('Array.from(document.querySelectorAll("button")).filter(b=>b.textContent.includes("查看来源（")).slice(-1)[0].click()')
        chrome.wait('document.querySelector("aside[aria-hidden=false]") && document.body.innerText.includes("doc-a.md") && document.body.innerText.includes("doc-b.md")')
        record['checks'].append('PLAN card shows grounded parameter and missing parameter, source panel uses same two citations')
        time.sleep(0.6)  # Let the existing panel and message entry animations settle for the screenshot.
        screenshot = chrome.call('Page.captureScreenshot', {'format': 'png', 'captureBeyondViewport': False})
        (directory / 'plan.png').write_bytes(base64.b64decode(screenshot['data']))
        chrome.evaluate('document.querySelector(\'button[aria-label="关闭"]\').click()')
        chrome.evaluate('Array.from(document.querySelectorAll("[data-research-run]")).slice(-1)[0].querySelector("button").click()')
        deadline = time.monotonic() + 25
        while time.monotonic() < deadline:
            regeneration = json_url(backend + '/fixtures/summary')['data']['runs']
            if len(regeneration) == 3 and regeneration[-1]['status'] == 'COMPLETED':
                break
            time.sleep(0.2)
        assert len(regeneration) == 3 and regeneration[-1]['status'] == 'COMPLETED'
        record['checks'].append('regeneration creates one new run using shared service and preserves old artifacts')
        before_reload = json_url(backend + '/fixtures/summary')['data']['modelCalls']
        chrome.call('Page.reload')
        chrome.wait('document.querySelector(\'article[aria-label="计划草稿"]\')')
        # Existing MessageList settles its initial bottom position for 1.5 seconds.
        time.sleep(1.7)
        # The chat list virtualizes off-screen rows: open each restored artifact through the question rail.
        chrome.evaluate('document.querySelector(\'button[aria-label="comparison fixture"]\').click()')
        chrome.wait('document.body.innerText.includes("跨文档条件与操作比较")')
        chrome.evaluate('Array.from(document.querySelectorAll(\'button[aria-label="plan fixture"]\')).slice(-1)[0].click()')
        chrome.wait('document.querySelector(\'article[aria-label="计划草稿"]\')')
        assert json_url(backend + '/fixtures/summary')['data']['modelCalls'] == before_reload
        record['checks'].append('refresh restores REPORT/PLAN from database with no model restart')
        chrome.submit('生成计划', 'waiting fixture')
        chrome.wait('document.body.innerText.includes("本次计划的预算时限是多少")')
        waiting_calls = json_url(backend + '/fixtures/summary')['data']['modelCalls']
        chrome.call('Page.reload')
        chrome.wait('document.body.innerText.includes("本次计划的预算时限是多少")')
        assert json_url(backend + '/fixtures/summary')['data']['modelCalls'] == waiting_calls
        chrome.evaluate('''(() => { const e=document.querySelector('textarea[id^="research-input-"]'); Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set.call(e,'2 小时'); e.dispatchEvent(new Event('input',{bubbles:true})); })()''')
        chrome.button('继续研究')
        chrome.wait('document.body.innerText.includes("用户约束") && document.body.innerText.includes("2 小时")')
        record['checks'].append('WAITING_INPUT resumes through revision and displays user_input separately')
        chrome.submit('深入分析', 'cancel fixture')
        chrome.wait('document.body.innerText.includes("子任务") || document.body.innerText.includes("研究中")')
        chrome.button('取消研究')
        chrome.wait('document.body.innerText.includes("研究已取消")')
        record['checks'].append('cancel endpoint ends native worker requests without publishing artifact')
        chrome.call('Network.setBlockedURLs', {'urls': ['*api.github.com*', '*/events*', '*/conversations*']})
        chrome.submit('深入分析', 'slow comparison fixture')
        chrome.wait('document.body.innerText.includes("进度连接恢复中")')
        assert chrome.evaluate('document.querySelector(\'textarea[aria-label="聊天输入框"]\').value') == ''
        record['checks'].append('successful creation continues when conversation list refresh fails')
        chrome.call('Network.setBlockedURLs', {'urls': ['*api.github.com*']})
        # Wait on the persisted last run; existing report titles alone would be an invalid success assertion.
        deadline = time.monotonic() + 25
        while time.monotonic() < deadline:
            summary = json_url(backend + '/fixtures/summary')['data']
            if summary['runs'][-1]['status'] == 'COMPLETED':
                break
            time.sleep(0.2)
        assert summary['runs'][-1]['status'] == 'COMPLETED'
        last_id = summary['runs'][-1]['id']
        chrome.wait('document.querySelector(\'[data-research-run="' + last_id + '"]\')?.innerText.includes("研究完成")')
        record['checks'].append('progress connection failure reconnects by GET while same run continues')
        summary = json_url(backend + '/fixtures/summary')['data']
        assert len(summary['runs']) == 6
        cancelled = next(run for run in summary['runs'] if run['status'] == 'CANCELLED')
        assert not cancelled['has_artifact']
        record.update({'success': True, 'fixture_model_calls': summary['modelCalls'], 'runs': summary['runs'],
                       'research_creation_posts': sum(r['method'] == 'POST' and r['url'].endswith('/rag/research/runs') for r in chrome.requests),
                       'research_regeneration_posts': sum(r['method'] == 'POST' and r['url'].endswith('/regenerate') for r in chrome.requests),
                       'research_event_gets': sum(r['method'] == 'GET' and '/events?' in r['url'] for r in chrome.requests)})
        assert record['research_creation_posts'] == 5
        assert record['research_regeneration_posts'] == 1
        print(json.dumps(record, ensure_ascii=False, indent=2))
    finally:
        if chrome:
            chrome.socket.close()
        for process in reversed(processes):
            process.terminate()
        for process in reversed(processes):
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait(timeout=5)
        for log in logs:
            log.close()
        if created:
            docker('sh', '-c', 'exec dropdb -U "$POSTGRES_USER" "$1"', 'sh', database)
        shutil.rmtree(profile, ignore_errors=True)
        record['finished_at'] = datetime.now(timezone.utc).isoformat()
        (directory / 'summary.json').write_text(json.dumps(record, ensure_ascii=False, indent=2) + '\n')


if __name__ == '__main__':
    main()
