"""Blind contract tests for events.py (written from the contract only, implementation not read)."""
import json
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import events  # noqa: E402

FIXTURE = HERE / "fixtures" / "smoke1-haiku.jsonl"


# ---------- synthetic event builders ----------
def usage(i=0, o=0, cr=0, cc=0):
    return {"input_tokens": i, "output_tokens": o,
            "cache_read_input_tokens": cr, "cache_creation_input_tokens": cc}


def asst(mid, blocks, parent=None, u=None, model="m1"):
    return {"type": "assistant",
            "message": {"id": mid, "model": model, "content": blocks,
                        "usage": u if u is not None else usage(1, 1, 0, 0)},
            "parent_tool_use_id": parent}


def tu(tid, name, inp):
    return {"type": "tool_use", "id": tid, "name": name, "input": inp}


def text(t):
    return {"type": "text", "text": t}


def res(tid, content, is_error=False, parent=None):
    return {"type": "user",
            "message": {"content": [{"type": "tool_result", "tool_use_id": tid,
                                     "content": content, "is_error": is_error}]},
            "parent_tool_use_id": parent}


def result_ev(subtype="success", is_error=False, result="done", model_usage=None,
              cost=0.5, **extra):
    e = {"type": "result", "subtype": subtype, "is_error": is_error, "num_turns": 3,
         "total_cost_usd": cost, "result": result,
         "modelUsage": model_usage if model_usage is not None else {
             "m1": {"inputTokens": 1, "outputTokens": 2,
                    "cacheReadInputTokens": 3, "cacheCreationInputTokens": 4}}}
    e.update(extra)
    return e


def one_call(name, inp, result_text="", is_error=False, mid="msg_1", tid="toolu_1"):
    """Events for a single main-thread tool call."""
    return [asst(mid, [tu(tid, name, inp)]), res(tid, result_text, is_error)]


def seq(*calls_spec):
    """calls_spec: (name, input, result, is_error) — each in its own turn."""
    evs = []
    for n, (name, inp, out, err) in enumerate(calls_spec, start=1):
        evs += one_call(name, inp, out, err, mid=f"msg_{n}", tid=f"toolu_{n}")
    return evs


def calls_of(evs):
    cs, _ = events.calls(evs)
    return cs


def snippet(start, n, sep="→"):
    head = ("The file /work/app/A.kt has been updated. Here's the result of running "
            "`cat -n` on a snippet of the edited file:")
    lines = [f"{start + k:>6}{sep}line {start + k}" for k in range(n)]
    return head + "\n" + "\n".join(lines)


# ---------- load ----------
class TestLoad(unittest.TestCase):
    def test_blank_lines_ignored_bad_lines_counted(self):
        lines = ['{"type":"a"}\n', "\n", "   \n", "not json\n", '{"type":"b"}', '{"trunc',
                 ""]
        evs, bad = events.load(lines)
        self.assertEqual([e["type"] for e in evs], ["a", "b"])
        self.assertEqual(bad, 2)

    def test_fixture_loads_clean(self):
        raw = FIXTURE.read_text().splitlines()
        evs, bad = events.load(raw)
        self.assertEqual(bad, 0)
        self.assertEqual(len(evs), len([x for x in raw if x.strip()]))

    def test_truncated_last_line_from_kill(self):
        raw = FIXTURE.read_text().splitlines()
        raw[-1] = raw[-1][: len(raw[-1]) // 2]  # result line cut in half
        evs, bad = events.load(raw)
        self.assertEqual(bad, 1)
        self.assertFalse(any(e.get("type") == "result" for e in evs))
        self.assertEqual(events.end_reason(evs, False), "no_result")
        t = events.tokens(evs)
        self.assertTrue(t["partial_tokens"])
        self.assertIsNone(t["cost_usd"])


# ---------- fixture end-to-end ----------
class TestFixture(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.evs, _ = events.load(FIXTURE.read_text().splitlines())

    def test_calls_and_turns(self):
        cs, turns = events.calls(self.evs)
        self.assertEqual(turns, 3)  # three distinct main message ids
        self.assertEqual([c.name for c in cs], ["Read", "Bash", "Bash"])
        self.assertEqual([c.turn for c in cs], [1, 1, 2])
        self.assertEqual(cs[0].result, "1\thello\n2\t")
        self.assertEqual(cs[0].input, {"file_path": "/work/a.txt"})
        self.assertEqual(cs[0].id, "toolu_01TNrZWnAMn4kpNbbRW48yrx")
        self.assertFalse(any(c.sub for c in cs))
        self.assertFalse(any(c.is_error for c in cs))

    def test_tokens_from_model_usage(self):
        t = events.tokens(self.evs)
        self.assertEqual(t["total_tokens"], 6 + 1180 + 48614 + 10397)
        self.assertFalse(t["partial_tokens"])
        self.assertAlmostEqual(t["cost_usd"], 0.0031561400000000004)
        self.assertIn("claude-haiku-5-5", t["by_model"])

    def test_end_reason_and_audit(self):
        self.assertEqual(events.end_reason(self.evs, False), "normal")
        self.assertEqual(events.audit(calls_of(self.evs)), [])

    def test_init_info(self):
        info = events.init_info(self.evs)
        self.assertEqual(info["cli_version"], "2.1.308")
        self.assertEqual(info["model"], "claude-haiku-5-5")
        self.assertEqual(info["cwd"], "/work")
        self.assertEqual(info["permission_mode"], "bypassPermissions")
        self.assertEqual(info["mcp_servers"], [])
        self.assertEqual(info["plugins"], ["cc-plugin-agents-md", "cc-plugin-plugin-authoring"])
        self.assertEqual(info["memory_paths"], {"auto": "/home/agent/.claude/projects/-work/memory/"})

    def test_fixture_metrics_full_read(self):
        m = events.metrics(calls_of(self.evs), [])
        self.assertEqual(m["tool_calls"], 3)
        self.assertEqual(m["tools"], {"Read": 1, "Bash": 2})
        self.assertEqual(m["subagent_calls"], 0)
        self.assertEqual(m["lines_read"]["read"], 2)
        self.assertEqual(m["full_read_turns"], {"a.txt": 1})
        self.assertIsNone(m["first_targeted_turn"])
        self.assertIsNone(m["first_edit_turn"])


# ---------- calls / turns ----------
class TestCalls(unittest.TestCase):
    def test_same_message_id_multiple_events_one_turn(self):
        evs = [asst("msg_a", [{"type": "thinking", "thinking": ""}]),
               asst("msg_a", [tu("t1", "Read", {"file_path": "/work/x"})]),
               asst("msg_a", [tu("t2", "Read", {"file_path": "/work/y"})]),
               res("t1", "a"), res("t2", "b"),
               asst("msg_b", [text("ok")])]
        cs, turns = events.calls(evs)
        self.assertEqual(turns, 2)
        self.assertEqual([c.turn for c in cs], [1, 1])
        self.assertEqual([c.result for c in cs], ["a", "b"])

    def test_list_content_joined_with_newline(self):
        evs = one_call("Bash", {"command": "ls"},
                       [{"type": "text", "text": "a\nb"}, {"type": "text", "text": "c"}])
        self.assertEqual(calls_of(evs)[0].result, "a\nb\nc")

    def test_is_error_propagates(self):
        evs = one_call("Edit", {"file_path": "/work/a"}, "String not found", is_error=True)
        self.assertTrue(calls_of(evs)[0].is_error)

    def test_subagent_calls_flagged_and_keep_main_turn(self):
        evs = [asst("m1", [tu("t1", "Read", {"file_path": "/work/x"})]), res("t1", "x"),
               asst("m2", [tu("agent1", "Agent", {"prompt": "look"})]),
               asst("sub1", [tu("s1", "Read", {"file_path": "/work/y"})], parent="agent1"),
               res("s1", "y1\ny2", parent="agent1"),
               asst("sub2", [tu("s2", "Grep", {"pattern": "q"})], parent="agent1"),
               res("s2", "z", parent="agent1"),
               res("agent1", "summary"),
               asst("m3", [text("done")])]
        cs, turns = events.calls(evs)
        self.assertEqual(turns, 3)  # sub-agent messages are not main turns
        by_id = {c.id: c for c in cs}
        self.assertFalse(by_id["agent1"].sub)
        self.assertTrue(by_id["s1"].sub)
        self.assertTrue(by_id["s2"].sub)
        self.assertEqual(by_id["s1"].turn, 2)
        self.assertEqual(by_id["s2"].turn, 2)
        self.assertEqual(by_id["s1"].result, "y1\ny2")
        self.assertEqual(by_id["agent1"].result, "summary")

    def test_missing_tool_result_does_not_crash(self):
        evs = [asst("m1", [tu("t1", "Bash", {"command": "sleep 999"})])]
        cs, turns = events.calls(evs)
        self.assertEqual(turns, 1)
        self.assertEqual(len(cs), 1)
        self.assertIsInstance(cs[0].result, str)


# ---------- norm_path ----------
class TestNormPath(unittest.TestCase):
    def test_cases(self):
        self.assertEqual(events.norm_path("/work/app/A.kt"), "app/A.kt")
        self.assertEqual(events.norm_path("./app/A.kt"), "app/A.kt")
        self.assertEqual(events.norm_path("app/A.kt"), "app/A.kt")
        self.assertEqual(events.norm_path("/etc/passwd"), "/etc/passwd")
        self.assertEqual(events.norm_path("/workspace/A.kt"), "/workspace/A.kt")


# ---------- tokens ----------
class TestTokens(unittest.TestCase):
    def test_model_usage_multiple_models_summed(self):
        mu = {"opus": {"inputTokens": 1, "outputTokens": 10, "cacheReadInputTokens": 100,
                       "cacheCreationInputTokens": 1000, "costUSD": 9.0},
              "haiku": {"inputTokens": 2, "outputTokens": 20, "cacheReadInputTokens": 200,
                        "cacheCreationInputTokens": 2000}}
        evs = [asst("m1", [text("x")], u=usage(5, 5, 5, 5)), result_ev(model_usage=mu, cost=1.25)]
        t = events.tokens(evs)
        self.assertEqual(t["total_tokens"], 3333)
        self.assertFalse(t["partial_tokens"])
        self.assertEqual(t["cost_usd"], 1.25)
        self.assertEqual(set(t["by_model"]), {"opus", "haiku"})

    def test_partial_takes_last_usage_per_message_id(self):
        evs = [asst("m1", [{"type": "thinking", "thinking": ""}], u=usage(2, 1, 10, 5)),
               asst("m1", [tu("t1", "Read", {"file_path": "/work/a"})], u=usage(2, 50, 10, 5)),
               res("t1", "a"),
               asst("m2", [text("x")], u=usage(3, 7, 100, 0))]
        t = events.tokens(evs)
        self.assertTrue(t["partial_tokens"])
        self.assertIsNone(t["cost_usd"])
        self.assertEqual(t["total_tokens"], (2 + 50 + 10 + 5) + (3 + 7 + 100 + 0))

    def test_partial_includes_subagent_messages(self):
        evs = [asst("m1", [tu("a1", "Agent", {"prompt": "p"})], u=usage(1, 1, 1, 1)),
               asst("s1", [text("sub")], parent="a1", u=usage(10, 10, 10, 10))]
        self.assertEqual(events.tokens(evs)["total_tokens"], 44)

    def test_partial_no_assistant_is_zero(self):
        t = events.tokens([{"type": "system", "subtype": "init"}])
        self.assertTrue(t["partial_tokens"])
        self.assertEqual(t["total_tokens"], 0)


# ---------- end_reason ----------
class TestEndReason(unittest.TestCase):
    def test_timeout_wins(self):
        self.assertEqual(events.end_reason([result_ev()], True), "timeout")
        self.assertEqual(events.end_reason([], True), "timeout")

    def test_no_result(self):
        self.assertEqual(events.end_reason([asst("m", [text("x")])], False), "no_result")

    def test_budget(self):
        self.assertEqual(events.end_reason([result_ev("error_max_budget_usd", True)], False), "budget")
        self.assertEqual(events.end_reason([result_ev("error_max_budget_usd", False)], False), "budget")

    def test_usage_limit_variants(self):
        cases = [dict(result="Claude AI usage limit reached|1792933200"),
                 dict(result="API Error: Rate limit exceeded"),
                 dict(result="API Error: 529 Overloaded"),
                 dict(result="x", api_error_status=429),
                 dict(result="x", api_error_status="529"),
                 dict(result="Your limit reached for today")]
        for kw in cases:
            with self.subTest(kw=kw):
                ev = result_ev("error_during_execution", True, **kw) if "result" in kw else None
                self.assertEqual(events.end_reason([ev], False), "usage_limit")

    def test_plain_error(self):
        ev = result_ev("error_during_execution", True, result="something broke")
        self.assertEqual(events.end_reason([ev], False), "error")

    def test_limit_words_without_is_error_is_normal(self):
        ev = result_ev("success", False, result="I fixed the rate limit bug (429 handling)")
        self.assertEqual(events.end_reason([ev], False), "normal")


# ---------- audit ----------
class TestAudit(unittest.TestCase):
    def audit1(self, name, inp, out="", sub=False):
        if sub:
            evs = [asst("m1", [tu("a1", "Agent", {"prompt": "p"})]),
                   asst("s1", [tu("t1", name, inp)], parent="a1"),
                   res("t1", out, parent="a1")]
        else:
            evs = one_call(name, inp, out)
        return events.audit(calls_of(evs))

    def assertFlag(self, got, prefix, word):
        self.assertTrue(any(v.startswith(prefix) and word in v for v in got),
                        f"expected {prefix}*{word}* in {got}")

    def test_forbidden_tools(self):
        self.assertEqual(self.audit1("WebFetch", {"url": "http://localhost"}), ["tool:WebFetch"])
        self.assertIn("tool:WebSearch", self.audit1("WebSearch", {"query": "kotlin"}))

    def test_forbidden_strings_in_input(self):
        cases = [("Read", {"file_path": "/home/jun/.bashrc"}, "/home/jun"),
                 ("Bash", {"command": "ls ../clean-code-llm-experiment"}, "clean-code-llm-experiment"),
                 ("Grep", {"pattern": "x", "path": "../oracle/T1"}, "oracle"),
                 ("Read", {"file_path": "../../experiment/cases/T1/TASK.md"}, "experiment/"),
                 ("Bash", {"command": "cat ~/project/study-note/x"}, "study-note"),
                 ("Bash", {"command": "ls /x/graph-nav-experiment"}, "graph-nav-experiment"),
                 ("Bash", {"command": "ls ~/.cache/ccle-runner"}, "ccle-runner"),
                 ("Bash", {"command": "cat /home/agent/.claude/.credentials.json"}, "credentials")]
        for name, inp, word in cases:
            with self.subTest(inp=inp):
                self.assertFlag(self.audit1(name, inp), "input:", word)

    def test_non_violating_inputs(self):
        cases = [("Read", {"file_path": "/work/app/src/main/kotlin/Oracles.kt"}),  # Oracles != oracle (case)
                 ("Bash", {"command": "grep -r oracledb /work"}),  # not the word
                 ("Read", {"file_path": "/tmp/claude-x/abc"}),  # no digits
                 ("Read", {"file_path": "/tmp/claude-1000/abc"}),  # 계약 갱신: 컨테이너 안 CLI 임시 경로는 정상
                 ("Bash", {"command": "ls /home/agent"}),
                 ("Read", {"file_path": "/work/app/src/Experiment.kt"})]
        for name, inp in cases:
            with self.subTest(inp=inp):
                self.assertEqual(self.audit1(name, inp), [])

    def test_result_only_forbidden_string_not_violation(self):
        out = ("/home/jun/project/clean-code-llm-experiment/experiment/oracle\n"
               "study-note graph-nav-experiment ccle-runner /tmp/claude-1000 .credentials.json\n"
               "https://evil.example.com ssh git clone pip install")
        self.assertEqual(self.audit1("Bash", {"command": "ls -la"}, out), [])
        self.assertEqual(self.audit1("Read", {"file_path": "/work/a.txt"}, out), [])

    def test_bash_forbidden_commands(self):
        cases = [("ssh user@host", "ssh"), ("cd /work && scp a b:", "scp"),
                 ("sftp h", "sftp"), ("rsync -a x y", "rsync"), ("nc -z db 5432", "nc"),
                 ("ncat db 5432", "ncat"), ("gh pr list", "gh"), ("telnet db 5432", "telnet"),
                 ("git clone /x/y", "clone"), ("git fetch origin", "fetch"),
                 ("git pull", "pull"), ("git push origin main", "push"),
                 ("git ls-remote x", "ls-remote"), ("git remote -v", "remote"),
                 ("pip install requests", "pip"), ("pip3 install requests", "pip"),
                 ("npm i lodash", "npm"), ("npm install", "npm"), ("npx add x", "npx"),
                 ("apt-get install curl", "apt"), ("apt install jq", "apt"),
                 ("sdk install java 21", "sdk"), ("echo x | ssh h", "ssh")]
        for cmd, word in cases:
            with self.subTest(cmd=cmd):
                self.assertFlag(self.audit1("Bash", {"command": cmd}), "bash:", word)

    def test_bash_allowed_commands(self):
        for cmd in ["git status", "git diff", "git log --oneline", "ls ghost/", "echo sshd",
                    "./gradlew test 2>&1 | tail -50", "npm test", "psql -h db -U u -c 'select 1'",
                    "bash test.sh", "cat notes/greeting.txt"]:
            with self.subTest(cmd=cmd):
                self.assertEqual(self.audit1("Bash", {"command": cmd}), [])

    def test_local_urls_not_violation(self):
        for cmd in ["curl -s http://localhost:15480/pay", "curl http://127.0.0.1:8080/x",
                    "curl http://0.0.0.0:8080", "curl -X POST http://db:5432",
                    "curl 'http://[::1]:8080/a'", "curl https://localhost/x"]:
            with self.subTest(cmd=cmd):
                self.assertEqual(self.audit1("Bash", {"command": cmd}), [])

    def test_external_urls_flagged_with_host(self):
        cases = [("curl https://example.com/a", "example.com"),
                 ("wget http://evil.org:8443/x", "evil.org"),
                 ("curl ftp://files.example.net/f", "files.example.net"),
                 ("curl http://localhost.evil.com/x", "localhost.evil.com"),
                 ("curl http://user:pw@evil.com/x", "evil.com"),
                 ("curl http://localhost@evil.com/x", "evil.com"),
                 ("curl http://localhost:80@evil.com/x", "evil.com"),   # userinfo looks local
                 ("curl http://db:pw@evil.com/x", "evil.com"),
                 ("curl HTTPS://evil.com/x", "evil.com")]               # scheme is case-insensitive
        for cmd, host in cases:
            with self.subTest(cmd=cmd):
                got = self.audit1("Bash", {"command": cmd})
                self.assertIn(f"bash:url:{host}", got)

    def test_url_outside_bash_is_not_bash_url(self):
        got = self.audit1("Grep", {"pattern": "https://example.com", "path": "/work"})
        self.assertFalse(any(v.startswith("bash:url") for v in got), got)

    def test_sorted_unique_and_subagent_included(self):
        evs = seq(("Bash", {"command": "ssh a"}, "", False),
                  ("Bash", {"command": "ssh b"}, "", False),
                  ("WebFetch", {"url": "x"}, "", False),
                  ("Read", {"file_path": "/home/jun/a"}, "", False))
        got = events.audit(calls_of(evs))
        self.assertEqual(got, sorted(set(got)))
        self.assertEqual(len([v for v in got if "ssh" in v]), 1)
        self.assertIn("tool:WebFetch", got)
        self.assertFlag(self.audit1("Read", {"file_path": "/home/jun/x"}, sub=True), "input:", "/home/jun")


# ---------- parse_symbols ----------
class TestParseSymbols(unittest.TestCase):
    def test_formats(self):
        txt = ("# header comment\n\n"
               "app/src/A.kt:10-20 fix  rule threshold\n"
               "app/src/B.kt:7 read\n"
               "app/src/C.kt:1-3 read # trailing comment\n")
        sp = events.parse_symbols(txt)
        self.assertEqual([(s.path, s.start, s.end, s.kind) for s in sp],
                         [("app/src/A.kt", 10, 20, "fix"), ("app/src/B.kt", 7, 7, "read"),
                          ("app/src/C.kt", 1, 3, "read")])

    def test_errors(self):
        for bad in ["app/A.kt:20-10 fix", "app/A.kt fix", "app/A.kt:1-2", "app/A.kt:1-2 write",
                    "app/A.kt:x-2 fix", "garbage"]:
            with self.subTest(bad=bad):
                with self.assertRaises(ValueError):
                    events.parse_symbols(bad + "\n")

    def test_empty(self):
        self.assertEqual(events.parse_symbols("# only\n\n"), [])


# ---------- metrics ----------
SPAN_TXT = "app/A.kt:100-120 fix\napp/B.kt:50-60 read\n"


def spans():
    return events.parse_symbols(SPAN_TXT)


class TestMetricsBasics(unittest.TestCase):
    def test_counts_and_lines_read(self):
        evs = seq(("Read", {"file_path": "/work/x"}, "1\ta\n2\tb\n3\tc", False),
                  ("Grep", {"pattern": "q"}, "x:1:q\ny:2:q", False),
                  ("Bash", {"command": "ls"}, "a\nb\nc\nd", False),
                  ("Glob", {"pattern": "*"}, "f1", False),
                  ("Edit", {"file_path": "/work/x", "old_string": "a", "new_string": "b"},
                   snippet(1, 5), False),
                  ("Write", {"file_path": "/work/y", "content": "z"},
                   "File created successfully at: /work/y\nmore\nmore", False))
        m = events.metrics(calls_of(evs), [])
        self.assertEqual(m["tool_calls"], 6)
        self.assertEqual(m["tools"], {"Read": 1, "Grep": 1, "Bash": 1, "Glob": 1, "Edit": 1, "Write": 1})
        lr = m["lines_read"]
        self.assertEqual(lr, {"read": 3, "grep": 2, "bash": 4, "other": 1, "sub": 0})

    def test_subagent_lines_go_to_sub_and_subagent_calls(self):
        evs = [asst("m1", [tu("a1", "Agent", {"prompt": "p"})]),
               asst("s1", [tu("s1r", "Read", {"file_path": "/work/x"})], parent="a1"),
               res("s1r", "a\nb\nc", parent="a1"),
               asst("s2", [tu("s2b", "Bash", {"command": "ls"})], parent="a1"),
               res("s2b", "d\ne", parent="a1"),
               res("a1", "summary"),
               asst("m2", [tu("t2", "Task", {"prompt": "p2"})]), res("t2", "s2")]
        m = events.metrics(calls_of(evs), [])
        self.assertEqual(m["subagent_calls"], 2)
        self.assertEqual(m["lines_read"]["sub"], 5)
        self.assertEqual(m["lines_read"]["read"], 0)
        self.assertEqual(m["lines_read"]["bash"], 0)


class TestTargetedRead(unittest.TestCase):
    def read_turn(self, inp):
        evs = seq(("Bash", {"command": "ls"}, "x", False), ("Read", inp, "1\tx", False))
        return events.metrics(calls_of(evs), spans())

    def test_offset_limit_overlap(self):
        m = self.read_turn({"file_path": "/work/app/A.kt", "offset": 110, "limit": 5})
        self.assertEqual(m["first_targeted_turn"], 2)
        self.assertEqual(m["first_targeted_turn_by_span"].get("app/A.kt:100-120"), 2)

    def test_boundaries(self):
        cases = [({"offset": 81, "limit": 20}, True),   # 81..100
                 ({"offset": 80, "limit": 20}, False),  # 80..99
                 ({"offset": 120, "limit": 1}, True),
                 ({"offset": 121, "limit": 100}, False),
                 ({"offset": 1, "limit": 50}, False)]
        for kw, hit in cases:
            with self.subTest(kw=kw):
                m = self.read_turn(dict(file_path="/work/app/A.kt", **kw))
                self.assertEqual(m["first_targeted_turn"], 2 if hit else None)

    def test_offset_without_limit_uses_2000(self):
        m = self.read_turn({"file_path": "/work/app/A.kt", "offset": 1})  # 1..2000
        self.assertEqual(m["first_targeted_turn"], 2)
        m = self.read_turn({"file_path": "/work/app/A.kt", "offset": 1, "limit": 2000})
        self.assertEqual(m["first_targeted_turn"], 2)

    def test_full_read_is_not_targeted_saturation(self):
        evs = seq(("Read", {"file_path": "/work/app/A.kt"}, "\n".join(f"{i}\tx" for i in range(1, 1773)), False),
                  ("Read", {"file_path": "./app/A.kt"}, "again", False),
                  ("Read", {"file_path": "app/B.kt", "offset": 55, "limit": 3}, "x", False))
        m = events.metrics(calls_of(evs), spans())
        self.assertEqual(m["full_read_turns"], {"app/A.kt": 1})
        self.assertNotIn("app/A.kt:100-120", m["first_targeted_turn_by_span"])
        self.assertEqual(m["first_targeted_turn_by_span"].get("app/B.kt:50-60"), 3)
        self.assertEqual(m["first_targeted_turn"], 3)
        self.assertEqual(m["lines_read"]["read"], 1772 + 1 + 1)

    def test_other_file_same_range_not_targeted(self):
        m = self.read_turn({"file_path": "/work/app/Z.kt", "offset": 100, "limit": 10})
        self.assertIsNone(m["first_targeted_turn"])


class TestTargetedGrep(unittest.TestCase):
    def grep(self, inp, out):
        evs = seq(("Bash", {"command": "ls"}, "x", False), ("Grep", inp, out, False))
        return events.metrics(calls_of(evs), spans())

    def test_multi_file_match_line(self):
        m = self.grep({"pattern": "foo", "output_mode": "content", "-n": True},
                      "app/C.kt:3:foo\napp/A.kt:105:foo")
        self.assertEqual(m["first_targeted_turn"], 2)
        self.assertEqual(m["first_targeted_turn_by_span"].get("app/A.kt:100-120"), 2)

    def test_work_prefix_normalized(self):
        m = self.grep({"pattern": "foo"}, "/work/app/B.kt:60:foo")
        self.assertEqual(m["first_targeted_turn_by_span"].get("app/B.kt:50-60"), 2)

    def test_context_line_format(self):
        m = self.grep({"pattern": "foo", "-C": 2}, "app/A.kt:99:foo\napp/A.kt:100-ctx")
        self.assertEqual(m["first_targeted_turn"], 2)

    def test_outside_range(self):
        m = self.grep({"pattern": "foo"}, "app/A.kt:99:foo\napp/A.kt:121:foo\napp/B.kt:49:x")
        self.assertIsNone(m["first_targeted_turn"])

    def test_files_with_matches_mode_not_targeted(self):
        m = self.grep({"pattern": "foo"}, "Found 1 file\napp/A.kt")
        self.assertIsNone(m["first_targeted_turn"])

    def test_single_file_path_format(self):
        m = self.grep({"pattern": "foo", "path": "/work/app/A.kt", "output_mode": "content", "-n": True},
                      "3:foo\n110:foo")
        self.assertEqual(m["first_targeted_turn_by_span"].get("app/A.kt:100-120"), 2)
        m = self.grep({"pattern": "foo", "path": "/work/app/A.kt", "output_mode": "content", "-n": True},
                      "3:foo\n130:foo")
        self.assertIsNone(m["first_targeted_turn"])


class TestEdits(unittest.TestCase):
    def edit(self, path, out, err=False):
        return ("Edit", {"file_path": path, "old_string": "a", "new_string": "b"}, out, err)

    def test_edit_snippet_targets_and_hits(self):
        evs = seq(("Bash", {"command": "ls"}, "x", False), self.edit("/work/app/A.kt", snippet(110, 5)))
        m = events.metrics(calls_of(evs), spans())
        self.assertEqual(m["first_targeted_turn"], 2)
        self.assertEqual(m["first_edit_turn"], 2)
        self.assertEqual(events.norm_path(m["first_edit_path"]), "app/A.kt")
        self.assertIs(m["first_edit_hit"], True)
        self.assertEqual(m["edited_paths"], ["app/A.kt"])

    def test_tab_snippet(self):
        evs = seq(self.edit("/work/app/A.kt", snippet(95, 6, sep="\t")))  # 95..100
        m = events.metrics(calls_of(evs), spans())
        self.assertIs(m["first_edit_hit"], True)
        self.assertEqual(m["first_targeted_turn"], 1)

    def test_edit_outside_fix_is_miss(self):
        evs = seq(self.edit("/work/app/A.kt", snippet(1, 5)))
        m = events.metrics(calls_of(evs), spans())
        self.assertIs(m["first_edit_hit"], False)
        self.assertIsNone(m["first_targeted_turn"])

    def test_edit_on_read_span_is_targeted_but_not_hit(self):
        evs = seq(self.edit("/work/app/B.kt", snippet(55, 3)))
        m = events.metrics(calls_of(evs), spans())
        self.assertEqual(m["first_targeted_turn_by_span"].get("app/B.kt:50-60"), 1)
        self.assertIs(m["first_edit_hit"], False)

    def test_failed_edit_skipped(self):
        evs = seq(self.edit("/work/app/A.kt", "<tool_use_error>String to replace not found</tool_use_error>", True),
                  self.edit("/work/app/Z.kt", snippet(1, 3)),
                  self.edit("/work/app/A.kt", snippet(110, 3)))
        m = events.metrics(calls_of(evs), spans())
        self.assertEqual(m["first_edit_turn"], 2)
        self.assertEqual(events.norm_path(m["first_edit_path"]), "app/Z.kt")
        self.assertIs(m["first_edit_hit"], False)
        self.assertEqual(m["edited_paths"], ["app/Z.kt", "app/A.kt"])

    def test_no_fix_span_hit_none(self):
        evs = seq(self.edit("/work/app/A.kt", snippet(110, 3)))
        m = events.metrics(calls_of(evs), events.parse_symbols("app/B.kt:1-5 read\n"))
        self.assertIsNone(m["first_edit_hit"])
        m = events.metrics(calls_of(evs), [])
        self.assertIsNone(m["first_edit_hit"])

    def test_edited_paths_unique_ordered(self):
        evs = seq(self.edit("/work/app/B.kt", snippet(1, 2)),
                  self.edit("/work/app/A.kt", snippet(1, 2)),
                  self.edit("./app/B.kt", snippet(3, 2)),
                  ("MultiEdit", {"file_path": "/work/app/C.kt", "edits": []}, snippet(1, 2), False),
                  ("Write", {"file_path": "/work/app/D.kt", "content": "x"},
                   "File created successfully at: /work/app/D.kt", False))
        m = events.metrics(calls_of(evs), spans())
        self.assertEqual(m["edited_paths"], ["app/B.kt", "app/A.kt", "app/C.kt", "app/D.kt"])

    def test_edit_results_not_counted_in_lines(self):
        evs = seq(self.edit("/work/app/A.kt", snippet(1, 50)))
        m = events.metrics(calls_of(evs), spans())
        self.assertEqual(sum(m["lines_read"].values()), 0)

    def test_bash_edits_are_first_edit_with_hit_none(self):
        cmds = ["sed -i 's/a/b/' app/A.kt", "patch -p1 < x.diff", "echo x | tee notes/a.txt",
                "echo hi > notes/greeting.txt", "echo x >> build.gradle.kts",
                "cat > app/x.sql <<EOF\nselect 1\nEOF", "python - <<EOF\nprint(1)\nEOF",
                "printf x > c.yml", "echo y > d.yaml", "echo z > README.md", "echo s > run.sh"]
        for cmd in cmds:
            with self.subTest(cmd=cmd):
                evs = seq(("Bash", {"command": "ls"}, "x", False), ("Bash", {"command": cmd}, "", False),
                          self.edit("/work/app/A.kt", snippet(110, 2)))
                m = events.metrics(calls_of(evs), spans())
                self.assertEqual(m["first_edit_turn"], 2)
                self.assertIsNone(m["first_edit_hit"])
                self.assertEqual(m["edited_paths"], ["app/A.kt"])

    def test_bash_non_edits(self):
        cmds = ["./gradlew test 2>&1 | tail -50", "ls > /dev/null", "echo x > out.log",
                "cat app/A.kt", "grep -n foo app/A.kt", "bash test.sh 2>/dev/null"]
        for cmd in cmds:
            with self.subTest(cmd=cmd):
                evs = seq(("Bash", {"command": cmd}, "", False))
                m = events.metrics(calls_of(evs), spans())
                self.assertIsNone(m["first_edit_turn"])

    def test_failed_bash_edit_skipped(self):
        evs = seq(("Bash", {"command": "sed -i 's/a/b/' app/A.kt"}, "sed: no such file", True),
                  self.edit("/work/app/A.kt", snippet(110, 2)))
        m = events.metrics(calls_of(evs), spans())
        self.assertEqual(m["first_edit_turn"], 2)
        self.assertIs(m["first_edit_hit"], True)

    def test_earliest_targeted_across_kinds(self):
        evs = seq(("Read", {"file_path": "/work/app/A.kt"}, "x", False),
                  ("Grep", {"pattern": "f"}, "app/B.kt:55:f", False),
                  ("Read", {"file_path": "/work/app/A.kt", "offset": 100, "limit": 5}, "x", False),
                  self.edit("/work/app/A.kt", snippet(110, 2)))
        m = events.metrics(calls_of(evs), spans())
        self.assertEqual(m["first_targeted_turn"], 2)
        self.assertEqual(m["first_targeted_turn_by_span"],
                         {"app/B.kt:50-60": 2, "app/A.kt:100-120": 3})
        self.assertEqual(m["full_read_turns"], {"app/A.kt": 1})


if __name__ == "__main__":
    unittest.main()
