#!/usr/bin/env python3
"""Scriptable OpenAI-compatible upstream for research runs: no provider calls, no API cost.

Chat (/…/chat/completions) replays a fixed research policy with native tool calls, driven only by the
conversation it receives: search each SRC-nnn key named in the goal, read the first candidate, retry a
failed or empty search once, then finish; the main Agent delegates one worker per key when
conduct_research is offered and the goal names several keys. Requests without tools are artifact
generation and get a JSON report citing every supplied evidence ID.

Embeddings (/…/embeddings) are deterministic unit vectors. The seed is the first SRC-nnn token in the
text, or the whole normalized text when there is none, so a query naming a key ranks that key's
document first with cosine 1 and unrelated keys are near-orthogonal.

Faults are drawn per HTTP request from one seeded generator: no response (hold the connection, then
close it without a byte), 429 or 503, mid-stream disconnect (chat only), plus uniform latency.
Every request is appended to --log; GET /stats returns counters.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import random
import re
import threading
import time
import uuid
from functools import lru_cache
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

KEY = re.compile(r"SRC-\d{3}")
EVIDENCE = re.compile(r'"evidenceId"\s*:\s*"([^"]+)"')
VALUE = re.compile(r"recorded value for (SRC-\d{3}) is ([^.]+)\.")


# ---------------------------------------------------------------- embeddings

@lru_cache(maxsize=4096)
def vector(seed_text: str, dimension: int) -> tuple:
    digest = hashlib.sha256(seed_text.encode()).digest()
    generator = random.Random(int.from_bytes(digest, "big"))
    values = [generator.gauss(0.0, 1.0) for _ in range(dimension)]
    norm = math.sqrt(sum(v * v for v in values))
    return tuple(round(v / norm, 7) for v in values)


def embedding_seed(text: str) -> str:
    match = KEY.search(text)
    return match.group() if match else " ".join(text.lower().split())


def embeddings_response(body: dict) -> dict:
    texts = body.get("input")
    texts = [texts] if isinstance(texts, str) else list(texts or [])
    dimension = int(body.get("dimensions") or 1536)
    tokens = sum(max(1, len(t) // 4) for t in texts)
    return {"object": "list", "model": body.get("model", "stub"),
            "data": [{"object": "embedding", "index": i, "embedding": list(vector(embedding_seed(t), dimension))}
                     for i, t in enumerate(texts)],
            "usage": {"prompt_tokens": tokens, "total_tokens": tokens}}


# ---------------------------------------------------------------- chat policy

def text_of(content) -> str:
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return "".join(part.get("text", "") for part in content if isinstance(part, dict))
    return ""


def task_input(messages: list) -> dict:
    """First user message that is the serialized brief (main) or task (worker)."""
    for message in messages:
        if message.get("role") != "user":
            continue
        try:
            value = json.loads(text_of(message.get("content")))
        except ValueError:
            continue
        if isinstance(value, dict) and ("brief" in value or "task" in value):
            return value
    return {}


def interactions(messages: list) -> list:
    """(tool name, arguments, result text) in conversation order."""
    calls, result = {}, []
    for message in messages:
        for call in message.get("tool_calls") or []:
            function = call.get("function", {})
            try:
                arguments = json.loads(function.get("arguments") or "{}")
            except ValueError:
                arguments = {}
            calls[call.get("id")] = (function.get("name"), arguments)
        if message.get("role") == "tool":
            name, arguments = calls.get(message.get("tool_call_id"), (None, {}))
            result.append((name, arguments, text_of(message.get("content"))))
    return result


def unique_keys(text: str) -> list:
    keys = []
    for key in KEY.findall(text):
        if key not in keys:
            keys.append(key)
    return keys


def finding(key: str, evidence_id: str, excerpt: str) -> dict:
    value = dict(VALUE.findall(excerpt)).get(key)
    statement = "The recorded value for {} is {}.".format(key, value) if value else "A source describes {}.".format(key)
    return {"statement": statement, "evidenceIds": [evidence_id]}


def next_action(request: dict) -> tuple:
    """Pure policy: the same conversation always yields the same (tool, arguments)."""
    messages = request.get("messages") or []
    tools = {t.get("function", {}).get("name") for t in request.get("tools") or []}
    given = task_input(messages)
    worker = "task" in given
    goal = (given.get("task") or {}).get("goal", "") if worker else (given.get("brief") or {}).get("goal", "")
    keys = unique_keys(goal)
    history = interactions(messages)

    finishes = [h for h in history if h[0] == "finish_research"]
    if finishes:  # a rejected finish: give up evidence claims rather than loop
        return "finish_research", {"findings": [], "gaps": ["The stub policy could not finish with evidence."], "conflicts": []}

    if not worker and "conduct_research" in tools and len(keys) > 1:
        delegated = [h for h in history if h[0] == "conduct_research"]
        if not delegated:
            return "conduct_research", {"tasks": [{"goal": "Find the recorded value for {}.".format(k), "dimensions": ["recorded value"],
                                                   "expectedOutput": "One cited finding"} for k in keys]}
        cited = re.findall(r'"evidenceIds"\s*:\s*\[([^\]]*)\]', delegated[-1][2])
        ids = list(dict.fromkeys(i for group in cited for i in re.findall(r'"([^"]+)"', group)))
        findings = [{"statement": "Workers reported the recorded values.", "evidenceIds": ids}] if ids else []
        return "finish_research", {"findings": findings, "gaps": [] if ids else ["Workers returned no readable evidence."], "conflicts": []}

    index, attempts, pending, findings, gaps = 0, 0, None, [], []
    for name, _, text in history:
        if index >= len(keys):
            break
        key = keys[index]
        if name == "search_knowledge":
            ids = EVIDENCE.findall(text)
            if ids:
                pending = ids[0]
                continue
            attempts += 1
        elif name == "read_source" and pending is not None:
            if '"sourceState"' in text and pending in text:
                findings.append(finding(key, pending, text))
                index, attempts, pending = index + 1, 0, None
                continue
            attempts, pending = attempts + 1, None
        else:
            continue
        if attempts >= 2:
            gaps.append("No readable source was retrieved for {}.".format(key))
            index, attempts, pending = index + 1, 0, None
    if pending is not None:
        return "read_source", {"evidence_id": pending}
    if index < len(keys):
        return "search_knowledge", {"query": "{} recorded value".format(keys[index]), "limit": 3}
    if not keys:
        gaps.append("The goal names no SRC key.")
    return "finish_research", {"findings": findings, "gaps": gaps, "conflicts": []}


def artifact(request: dict) -> str:
    given = task_input(request.get("messages") or [])
    evidence = [e.get("evidenceId") for e in given.get("evidence") or [] if e.get("evidenceId")]
    statements = [f.get("statement", "") for f in (given.get("findings") or {}).get("findings") or []]
    sections = [{"heading": "Answer", "text": " ".join(statements) or "See the cited sources.", "evidenceIds": evidence}] if evidence else []
    return json.dumps({"title": "Stub research report", "sections": sections, "plan": None,
                       "gaps": [] if evidence else ["No readable source evidence was available."]})


# ---------------------------------------------------------------- server

class Faults:
    def __init__(self, args):
        self.random = random.Random(args.seed)
        self.lock = threading.Lock()
        self.args = args

    def draw(self, endpoint: str) -> str:
        hang, error = getattr(self.args, endpoint + "_hang"), getattr(self.args, endpoint + "_error")
        drop = self.args.chat_drop if endpoint == "chat" else 0.0
        with self.lock:
            roll, which = self.random.random(), self.random.random()
        if roll < hang:
            return "hang"
        if roll < hang + error:
            return "429" if which < 0.5 else "503"
        if roll < hang + error + drop:
            return "drop"
        return "none"

    def latency(self, endpoint: str) -> float:
        low, high = getattr(self.args, endpoint + "_latency_ms")
        with self.lock:
            return self.random.uniform(low, high) / 1000


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "stub-upstream"

    def log_message(self, *_):
        pass

    def do_GET(self):
        if self.path.rstrip("/") in ("/healthz", "/stats"):
            with self.server.lock:
                payload = json.dumps({"status": "UP", "counters": self.server.counters, "config": self.server.config}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        else:
            self.send_error(404)

    def do_POST(self):
        started = time.monotonic()
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length") or 0)) or b"{}")
        endpoint = "embedding" if self.path.rstrip("/").endswith("/embeddings") else "chat" if self.path.rstrip("/").endswith("/chat/completions") else None
        if endpoint is None:
            self.send_error(404)
            return
        fault = self.server.faults.draw(endpoint)
        record = {"at": time.time(), "endpoint": endpoint, "fault": fault, "path": self.path}
        try:
            time.sleep(self.server.faults.latency(endpoint))
            if fault == "hang":
                time.sleep(self.server.args.hang_seconds)
                self.close_connection = True
                record["status"] = "no_response"
                return
            if fault in ("429", "503"):
                self.json(int(fault), {"error": {"message": "stub injected " + fault, "code": "stub_" + fault}})
                record["status"] = int(fault)
                return
            if endpoint == "embedding":
                self.json(200, embeddings_response(body))
                record.update(status=200, inputs=len(body.get("input") or []) if not isinstance(body.get("input"), str) else 1)
                return
            record.update(self.chat(body, fault == "drop"))
        except (BrokenPipeError, ConnectionResetError):
            record["status"] = "client_closed"
        finally:
            record["elapsed_ms"] = round((time.monotonic() - started) * 1000)
            self.server.observe(record)

    def json(self, status: int, value: dict):
        payload = json.dumps(value).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def chat(self, body: dict, drop: bool) -> dict:
        tools = bool(body.get("tools"))
        if tools:
            name, arguments = next_action(body)
            delta = {"role": "assistant", "content": None, "tool_calls": [{"index": 0, "id": "call_" + uuid.uuid4().hex[:12], "type": "function",
                                                                        "function": {"name": name, "arguments": json.dumps(arguments)}}]}
            finish, summary = "tool_calls", {"tool": name}
        else:
            delta, finish, summary = {"role": "assistant", "content": artifact(body)}, "stop", {"tool": None}
        prompt = max(1, len(json.dumps(body.get("messages") or [])) // 4)
        completion = max(1, len(json.dumps(delta)) // 4)
        usage = {"prompt_tokens": prompt, "completion_tokens": completion, "total_tokens": prompt + completion,
                 "prompt_tokens_details": {"cached_tokens": 0}}
        identifier = "stub-" + uuid.uuid4().hex[:12]
        if not body.get("stream"):
            self.json(200, {"id": identifier, "object": "chat.completion", "model": body.get("model"),
                            "choices": [{"index": 0, "message": delta, "finish_reason": finish}], "usage": usage})
            return {"status": 200, **summary}
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True
        frame = {"id": identifier, "object": "chat.completion.chunk", "model": body.get("model"),
                 "choices": [{"index": 0, "delta": delta, "finish_reason": finish}], "usage": usage}
        if drop:  # half a frame, then the connection closes without [DONE]
            text = "data: " + json.dumps(frame)
            self.wfile.write(text[: len(text) // 2].encode())
            self.wfile.flush()
            return {"status": "dropped", **summary}
        self.wfile.write(("data: " + json.dumps(frame) + "\n\ndata: [DONE]\n\n").encode())
        self.wfile.flush()
        return {"status": 200, **summary}


class StubServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, args):
        super().__init__((args.host, args.port), Handler)
        self.args, self.faults, self.lock = args, Faults(args), threading.Lock()
        self.counters = {}
        self.config = {k: v for k, v in vars(args).items() if k != "log"}
        self.log = Path(args.log).open("a") if args.log else None

    def observe(self, record: dict):
        with self.lock:
            key = "{}:{}".format(record["endpoint"], record.get("status"))
            self.counters[key] = self.counters.get(key, 0) + 1
            if self.log:
                self.log.write(json.dumps(record) + "\n")
                self.log.flush()


def latency(value: str) -> tuple:
    low, _, high = value.partition(":")
    return float(low), float(high or low)


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    result.add_argument("--host", default="127.0.0.1")
    result.add_argument("--port", type=int, default=18080)
    result.add_argument("--seed", type=int, default=7)
    result.add_argument("--log", help="append one JSON line per request")
    result.add_argument("--hang-seconds", type=float, default=90.0, help="how long a no-response request holds the connection")
    for endpoint, low_high in (("embedding", "5:30"), ("chat", "20:80")):
        result.add_argument("--{}-hang".format(endpoint), type=float, default=0.0, help="probability of no response")
        result.add_argument("--{}-error".format(endpoint), type=float, default=0.0, help="probability of 429/503")
        result.add_argument("--{}-latency-ms".format(endpoint), type=latency, default=latency(low_high), help="uniform range low:high")
    result.add_argument("--chat-drop", type=float, default=0.0, help="probability of a mid-stream disconnect")
    return result


def main():
    args = parser().parse_args()
    server = StubServer(args)
    print("stub upstream listening on http://{}:{}".format(args.host, server.server_address[1]), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
