import json
import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from stub_upstream import artifact, embedding_seed, next_action, vector
from x3_upstream import HONEST, SUCCESS, WRONG, cases, classify

SEARCH_TOOLS = [{"function": {"name": n}} for n in ("search_knowledge", "read_source", "finish_research")]


def brief(goal):
    # Explicit prompt caching sends content blocks; the policy must read both shapes.
    return {"role": "user", "content": [{"type": "text", "text": json.dumps({"brief": {"goal": goal}})}]}


def exchange(call_id, name, arguments, result):
    return [{"role": "assistant", "tool_calls": [{"id": call_id, "function": {"name": name, "arguments": json.dumps(arguments)}}]},
            {"role": "tool", "tool_call_id": call_id, "content": result}]


class PolicyTest(unittest.TestCase):
    def test_search_read_finish_for_each_key(self):
        messages = [{"role": "system", "content": "static"}, brief("Compare SRC-004 and SRC-009.")]
        self.assertEqual(("search_knowledge", {"query": "SRC-004 recorded value", "limit": 3}),
                         next_action({"messages": messages, "tools": SEARCH_TOOLS}))
        messages += exchange("1", "search_knowledge", {}, '[{"evidenceId":"ev-a","text":"SRC-004"}]')
        self.assertEqual(("read_source", {"evidence_id": "ev-a"}), next_action({"messages": messages, "tools": SEARCH_TOOLS}))
        messages += exchange("2", "read_source", {}, '{"evidence":{"evidenceId":"ev-a","text":"The recorded value for SRC-004 is 128 units."},"sourceState":"CURRENT"}')
        self.assertEqual("SRC-009 recorded value", next_action({"messages": messages, "tools": SEARCH_TOOLS})[1]["query"])
        messages += exchange("3", "search_knowledge", {}, "SEARCH_FAILED phase=embedding.http code=RETRIEVAL_DEADLINE")
        messages += exchange("4", "search_knowledge", {}, "[]")
        tool, arguments = next_action({"messages": messages + [{"role": "user", "content": "reminder"}], "tools": SEARCH_TOOLS})
        self.assertEqual("finish_research", tool)
        self.assertEqual([{"statement": "The recorded value for SRC-004 is 128 units.", "evidenceIds": ["ev-a"]}], arguments["findings"])
        self.assertEqual(["No readable source was retrieved for SRC-009."], arguments["gaps"])

    def test_main_agent_delegates_one_worker_per_key(self):
        tools = SEARCH_TOOLS + [{"function": {"name": "conduct_research"}}]
        messages = [brief("Compare SRC-001 and SRC-002.")]
        tool, arguments = next_action({"messages": messages, "tools": tools})
        self.assertEqual(("conduct_research", 2), (tool, len(arguments["tasks"])))
        messages += exchange("1", "conduct_research", arguments, '[{"findings":[{"statement":"x","evidenceIds":["ev-1"]}]},{"findings":[{"statement":"y","evidenceIds":["ev-2"]}]}]')
        tool, arguments = next_action({"messages": messages, "tools": tools})
        self.assertEqual(("finish_research", ["ev-1", "ev-2"]), (tool, arguments["findings"][0]["evidenceIds"]))

    def test_artifact_cites_supplied_evidence_or_reports_gap(self):
        cited = json.loads(artifact({"messages": [{"role": "user", "content": json.dumps({"brief": {}, "evidence": [{"evidenceId": "ev-1"}]})}]}))
        self.assertEqual(["ev-1"], cited["sections"][0]["evidenceIds"])
        empty = json.loads(artifact({"messages": [{"role": "user", "content": json.dumps({"brief": {}, "evidence": []})}]}))
        self.assertEqual(([], 1), (empty["sections"], len(empty["gaps"])))


class EmbeddingTest(unittest.TestCase):
    def test_key_decides_the_vector(self):
        self.assertEqual("SRC-007", embedding_seed("SRC-007 stub inspection record. The recorded value ..."))
        query, document, other = (vector(embedding_seed(t), 1536) for t in ("SRC-007 recorded value", "SRC-007 stub inspection record", "SRC-008 x"))
        self.assertAlmostEqual(1.0, sum(a * b for a, b in zip(query, document)), places=5)
        self.assertLess(abs(sum(a * b for a, b in zip(query, other))), 0.15)


class X3Test(unittest.TestCase):
    def test_fixed_tasks_and_outcome_classes(self):
        tasks = cases(50)
        self.assertEqual(50, len({t["id"] for t in tasks}))
        self.assertIn("SRC-001 and SRC-002", tasks[25]["goal"])
        run = {"brief": {"goal": "Compare SRC-001 and SRC-002."}, "status": "COMPLETED", "artifact": {"title": "t"}}
        both = [{"documentName": "SRC-001 stub"}, {"documentName": "SRC-002 stub"}]
        self.assertEqual(SUCCESS, classify({"run": run, "sources": both}))
        self.assertEqual(WRONG, classify({"run": run, "sources": both[:1]}))
        self.assertEqual(HONEST, classify({"run": {**run, "status": "PARTIAL"}, "sources": []}))


if __name__ == "__main__":
    unittest.main()
