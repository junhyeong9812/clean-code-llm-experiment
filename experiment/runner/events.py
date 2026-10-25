"""stream-json 이벤트 → 지표·감사 (ADR-0005 §5·§6.4). 실행·파일 접근 없는 순수 함수만 둔다.

이벤트 형식(claude 2.1.308 스모크 캡처 `tests/fixtures/`): content 블록마다 assistant 이벤트가 나오고 같은 블록 묶음은
message.id 가 같다. 도구 결과는 user 이벤트의 tool_result 블록(tool_use_id 로 짝). 하위 에이전트 메시지는
parent_tool_use_id 가 있다.
"""
import json
import re
from dataclasses import dataclass
from urllib.parse import urlsplit

WORK = "/work"
EDIT_TOOLS = ("Edit", "Write", "MultiEdit", "NotebookEdit")
READ_DEFAULT_LIMIT = 2000  # Read 도구 기본 상한(줄)

# 감사: 도구 *입력*에만 적용한다. 결과(파일 내용)까지 보면 코드 안 문자열 때문에 오탐이 난다(앞 실험).
AUDIT_INPUT = [r"/home/jun", r"clean-code-llm-experiment", r"\boracle\b", r"experiment/", r"study-note",
               r"graph-nav-experiment", r"ccle-runner", r"/tmp/claude-\d+", r"\.credentials\.json"]
FORBIDDEN_TOOLS = {"WebFetch", "WebSearch"}
# 외부로 나가는 명령. curl·wget 은 로컬 서비스(앱·가짜 PG·DB) 디버깅에 정당하게 쓰이므로 호스트가 로컬이 아닐 때만.
AUDIT_BASH = [r"(^|[\s;&|(])(nc|ncat|ssh|scp|sftp|rsync|gh|telnet)\s", r"\bgit\s+(clone|fetch|pull|push|ls-remote|remote)\b",
              r"\b(pip3?|npm|npx|apt(-get)?|sdk)\s+(install|i|add)\b"]
# scheme 있는 URL 전부(대소문자 무시). 호스트는 urlsplit 으로 — userinfo(`localhost@evil.com`)·포트·IPv6 괄호 처리.
# 한계: scheme 없는 `curl evil.com/x` 는 잡지 않는다(감사는 보조 — 외부 접속 차단은 아님, ADR §6.1).
URL = re.compile(r"(?i)\b(?:https?|ftp)://[^\s'\"<>]+")
LOCAL_HOSTS = {"localhost", "127.0.0.1", "0.0.0.0", "db", "::1"}


def url_hosts(cmd: str) -> list:
    out = []
    for u in URL.findall(cmd):
        try:
            h = urlsplit(u).hostname or ""
        except ValueError:
            h = ""
        out.append(h or u)  # 호스트를 못 읽으면 URL 그대로(로컬로 간주하지 않는다)
    return out


def load(lines) -> list:
    """jsonl 줄 → 이벤트 목록. 깨진 줄은 건너뛰고 개수를 센다(마지막 줄이 강제 종료로 잘릴 수 있다)."""
    evs, bad = [], 0
    for line in lines:
        line = line.strip()
        if not line:
            continue
        try:
            evs.append(json.loads(line))
        except json.JSONDecodeError:
            bad += 1
    return evs, bad


@dataclass
class Call:
    turn: int
    name: str
    input: dict
    id: str
    sub: bool  # 하위 에이전트 안의 호출
    result: str = ""
    is_error: bool = False
    meta: dict = None  # user 이벤트의 tool_use_result(구조화 결과 — Read 의 file.startLine, Edit 의 originalFile 등)


def calls(evs: list) -> tuple:
    """(도구 호출 목록, 본 대화 턴 수). 턴 = 본 대화 assistant 메시지 id 개수. 하위 에이전트 호출은 그때의 본 턴에 귀속."""
    turn, seen, out, by_id = 0, set(), [], {}
    for ev in evs:
        t = ev.get("type")
        if t == "assistant":
            msg = ev.get("message") or {}
            sub = bool(ev.get("parent_tool_use_id"))
            mid = msg.get("id")
            if not sub and mid not in seen:
                seen.add(mid)
                turn += 1
            for b in msg.get("content") or []:
                if b.get("type") in ("tool_use", "server_tool_use"):
                    c = Call(turn, b.get("name", "?"), b.get("input") or {}, b.get("id", ""), sub)
                    out.append(c)
                    by_id[c.id] = c
        elif t == "user":
            blocks = [b for b in (ev.get("message") or {}).get("content") or []
                      if isinstance(b, dict) and b.get("type") == "tool_result" and b.get("tool_use_id") in by_id]
            meta = ev.get("tool_use_result")
            for b in blocks:
                c = by_id[b["tool_use_id"]]
                c.result = _text(b.get("content"))
                c.is_error = bool(b.get("is_error"))
                if len(blocks) == 1 and isinstance(meta, dict):  # 이벤트 하나에 결과 하나일 때만 짝이 확실하다
                    c.meta = meta
    return out, turn


def _text(content) -> str:
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return "\n".join(x.get("text", "") for x in content if isinstance(x, dict))
    return ""


def _lines(s: str) -> int:
    return len(s.splitlines()) if s else 0


def norm_path(p: str) -> str:
    """사본 안 경로를 사본 루트 기준 상대 경로로. 사본 밖이면 그대로(앞에 / 유지)."""
    if not p:
        return ""
    p = re.sub(r"/+", "/", p)
    if p.startswith(WORK + "/"):
        return p[len(WORK) + 1:]
    if p.startswith("/"):
        return p
    return p[2:] if p.startswith("./") else p


# ---------------------------------------------------------------- 토큰

def _usage_sum(u: dict, keys) -> int:
    return sum(int(u.get(k) or 0) for k in keys)


MU_KEYS = ("inputTokens", "outputTokens", "cacheReadInputTokens", "cacheCreationInputTokens")
U_KEYS = ("input_tokens", "output_tokens", "cache_read_input_tokens", "cache_creation_input_tokens")


def tokens(evs: list) -> dict:
    """총 토큰. result 가 있으면 modelUsage 합(하위 에이전트 포함 — result.usage 는 본 대화만).
    result 가 없으면(시간 상한 강제 종료) assistant 메시지 id 마다 마지막 usage 를 더한 부분 합(partial)."""
    res = next((e for e in reversed(evs) if e.get("type") == "result"), None)
    if res is not None:
        mu = res.get("modelUsage") or {}
        return {"total_tokens": sum(_usage_sum(v, MU_KEYS) for v in mu.values()),
                "by_model": {m: {k: int(v.get(k) or 0) for k in MU_KEYS} for m, v in mu.items()},
                "cost_usd": res.get("total_cost_usd"), "partial_tokens": False, "has_modelusage": bool(mu)}
    last = {}
    for e in evs:
        if e.get("type") == "assistant":
            m = e.get("message") or {}
            if m.get("usage"):
                last[m.get("id")] = (m.get("model"), m["usage"])
    by_model = {}
    for model, u in last.values():
        d = by_model.setdefault(model or "?", {k: 0 for k in MU_KEYS})
        for mk, uk in zip(MU_KEYS, U_KEYS):
            d[mk] += int(u.get(uk) or 0)
    return {"total_tokens": sum(sum(d.values()) for d in by_model.values()), "by_model": by_model,
            "cost_usd": None, "partial_tokens": True, "has_modelusage": False}


# ---------------------------------------------------------------- 종료 원인

LIMIT_TEXT = re.compile(r"usage limit|rate limit|limit reached|overloaded|429|529", re.I)


def end_reason(evs: list, timed_out: bool) -> str:
    """normal | timeout | budget | usage_limit | error | no_result"""
    if timed_out:
        return "timeout"
    res = next((e for e in reversed(evs) if e.get("type") == "result"), None)
    if res is None:
        return "no_result"
    sub = str(res.get("subtype") or "")
    if "budget" in sub:
        return "budget"
    if res.get("is_error"):
        txt = f"{res.get('result') or ''} {res.get('api_error_status') or ''} {sub}"
        return "usage_limit" if LIMIT_TEXT.search(txt) else "error"
    return "normal"


# ---------------------------------------------------------------- 감사

def audit(cs: list) -> list:
    hits = set()
    for c in cs:
        if c.name in FORBIDDEN_TOOLS:
            hits.add(f"tool:{c.name}")
        text = json.dumps(c.input, ensure_ascii=False)
        hits.update(f"input:{p}" for p in AUDIT_INPUT if re.search(p, text))
        if c.name == "Bash":
            cmd = str(c.input.get("command", ""))
            hits.update(f"bash:{p}" for p in AUDIT_BASH if re.search(p, cmd))
            for host in url_hosts(cmd):
                if host.lower() not in LOCAL_HOSTS:
                    hits.add(f"bash:url:{host}")
    return sorted(hits)


# ---------------------------------------------------------------- 탐색 지표

@dataclass
class Span:
    path: str
    start: int
    end: int
    kind: str  # fix | read


def parse_symbols(text: str) -> list:
    """oracle symbols.<구조>.txt: 줄마다 `경로:시작-끝 fix|read [메모]`(경로는 사본 루트 기준). # 주석·빈 줄 무시."""
    out = []
    for raw in text.splitlines():
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        m = re.fullmatch(r"(\S+):(\d+)(?:-(\d+))?\s+(fix|read)\b.*", line)
        if not m:
            raise ValueError(f"symbols 형식 오류: {raw!r}")
        s = int(m[2])
        e = int(m[3] or s)
        if e < s:
            raise ValueError(f"symbols 범위 역순: {raw!r}")
        out.append(Span(m[1], s, e, m[4]))
    return out


GREP_LINE = re.compile(r"^(?P<path>[^\s:][^:]*?):(?P<line>\d+)[:-]", re.M)
SNIPPET_LINE = re.compile(r"^\s*(\d+)[\t→]", re.M)


def _overlap(a1, a2, b1, b2) -> bool:
    return a1 <= b2 and b1 <= a2


def targeted(c: Call, spans: list) -> list:
    """이 호출이 겨냥한 span 목록. 겨냥 = offset 을 준 Read 의 범위가 span 과 겹침 · Grep 결과 줄이 span 안 ·
    Edit 결과 스니펫 줄이 span 과 겹침. offset 없는 Read(파일 전체)는 겨냥이 아니다(full_read 로 따로)."""
    hit = []
    if c.name == "Read" and c.input.get("offset") is not None:
        p = norm_path(str(c.input.get("file_path", "")))
        f = (c.meta or {}).get("file") or {}
        if f.get("startLine") is not None and f.get("numLines") is not None:  # 실제로 돌려받은 범위
            s, e = int(f["startLine"]), int(f["startLine"]) + max(int(f["numLines"]), 1) - 1
        else:
            s = int(c.input["offset"])
            e = s + int(c.input.get("limit") or READ_DEFAULT_LIMIT) - 1
        hit = [sp for sp in spans if sp.path == p and _overlap(s, e, sp.start, sp.end)]
    elif c.name == "Grep" and c.result:
        base = norm_path(str(c.input.get("path", "")))
        paths = {sp.path for sp in spans}
        for m in GREP_LINE.finditer(c.result):
            p = norm_path(m["path"])
            if p not in paths and base and not p.startswith("/"):  # 상대 경로 결과는 검색 위치 기준
                p = f"{base.rstrip('/')}/{p}"
            n = int(m["line"])
            hit += [sp for sp in spans if sp.path == p and sp.start <= n <= sp.end and sp not in hit]
        if not hit and base in paths:  # 단일 파일 Grep 은 결과에 경로가 없다: "12:..." 형식
            for m in re.finditer(r"^(\d+)[:-]", c.result, re.M):
                n = int(m[1])
                hit += [sp for sp in spans if sp.path == base and sp.start <= n <= sp.end and sp not in hit]
    elif c.name in EDIT_TOOLS:
        hit = edit_spans(c, spans)
    elif c.name == "Bash" and c.result:
        hit = bash_spans(c, spans)
    return hit


# Bash 로 읽기(이 CLI 빌드에는 Grep 도구가 없다 — 스모크 캡처): grep/rg -n 결과 줄 · sed -n 'A,Bp' 파일
GREP_CMD = re.compile(r"(^|[\s;&|(])(grep|egrep|rg)\s")
BASH_GREP_LINE = re.compile(r"^(?:(?P<path>[^\s:]+?)[:-])?(?P<line>\d+)[:-]", re.M)
SED_RANGE = re.compile(r"\bsed\s+-n\s+['\"]?(\d+)(?:,(\d+))?p['\"]?\s+['\"]?([^\s;|&'\"]+)")


CAT_CMD = re.compile(r"(?:^|[\s;&|(])cat\s+((?:-\w+\s+)*)([^;&|<>]+)")


def bash_cat_paths(cmd: str, paths: set) -> list:
    out = []
    for m in CAT_CMD.finditer(cmd):
        for tok in m[2].split():
            p = norm_path(tok.strip("'\""))
            if p in paths and p not in out:
                out.append(p)
    return out


def bash_spans(c: Call, spans: list) -> list:
    cmd = str(c.input.get("command", ""))
    paths = {sp.path for sp in spans}
    hit = []

    def add(p, s, e):
        for sp in spans:
            if sp.path == p and _overlap(s, e, sp.start, sp.end) and sp not in hit:
                hit.append(sp)

    for m in SED_RANGE.finditer(cmd):
        s = int(m[1])
        add(norm_path(m[3]), s, int(m[2] or s))
    if GREP_CMD.search(cmd):
        named = [p for p in paths if re.search(rf"(^|[\s'\"/=]){re.escape(p)}($|[\s'\";|&)])", cmd)
                 or f"{WORK}/{p}" in cmd]
        for m in BASH_GREP_LINE.finditer(c.result):
            p = norm_path(m["path"]) if m["path"] else (named[0] if len(named) == 1 else None)
            if p:
                n = int(m["line"])
                add(p, n, n)
    return hit


def edit_lines(c: Call) -> list:
    return [int(x) for x in SNIPPET_LINE.findall(c.result or "")]


def edit_range(c: Call):
    """편집이 닿은 *원래 파일* 기준 줄 범위 (lo, hi) 또는 None.
    ① tool_use_result.structuredPatch 의 '-' 줄(맥락 줄 제외) ② originalFile 안 oldString 위치 ③ 결과 텍스트 스니펫 줄 번호.
    (2.1.308 Edit 결과 텍스트는 "updated successfully" 뿐이라 ③은 대비용)"""
    m = c.meta or {}
    touched = []  # hunk 의 맥락 줄(' ')은 빼고 지운 줄('-'), 순수 삽입이면 삽입 위치의 원래 줄
    for h in m.get("structuredPatch") or []:
        if not (isinstance(h, dict) and h.get("oldStart") and isinstance(h.get("lines"), list)):
            continue
        n = int(h["oldStart"])
        for ln in h["lines"]:
            if ln.startswith("-"):
                touched.append(n)
                n += 1
            elif ln.startswith("+"):
                touched.append(max(n - 1, 1))  # 원래 n-1 번째 줄 뒤에 삽입
            else:
                n += 1
    if touched:
        return min(touched), max(touched)
    orig, old = m.get("originalFile"), m.get("oldString")
    if isinstance(orig, str) and isinstance(old, str) and old and orig.find(old) >= 0:
        i = orig.find(old)
        lo = orig.count("\n", 0, i) + 1
        return lo, lo + old.rstrip("\n").count("\n")
    ns = edit_lines(c)
    return (min(ns), max(ns)) if ns else None


def edit_spans(c: Call, spans: list) -> list:
    p = norm_path(str(c.input.get("file_path", "")))
    r = edit_range(c)
    if not r:
        return []
    return [sp for sp in spans if sp.path == p and _overlap(r[0], r[1], sp.start, sp.end)]


BASH_EDIT = re.compile(r"\bsed\s+-i|\bpatch\b|\btee\b|>\s*[\w./-]+\.(kt|kts|ya?ml|sql|md|txt|sh)\b|python3?\s+-\s*<<|cat\s+>")


def metrics(cs: list, spans: list) -> dict:
    out = {"tool_calls": len(cs), "tools": {}, "subagent_calls": 0,
           "lines_read": {"read": 0, "grep": 0, "bash": 0, "other": 0, "sub": 0},
           "first_targeted_turn": None, "first_targeted_turn_by_span": {}, "full_read_turns": {},
           "first_edit_turn": None, "first_edit_path": None, "first_edit_hit": None, "edited_paths": []}
    keys = {f"{sp.path}:{sp.start}-{sp.end}": sp for sp in spans}
    for c in cs:
        out["tools"][c.name] = out["tools"].get(c.name, 0) + 1
        if c.name in ("Agent", "Task"):
            out["subagent_calls"] += 1
        if c.name not in EDIT_TOOLS:  # 편집 결과 스니펫은 읽기가 아니다. 하위 에이전트 안의 읽기는 sub 로 따로
            k = "sub" if c.sub else {"Read": "read", "Grep": "grep", "Bash": "bash"}.get(c.name, "other")
            out["lines_read"][k] += _lines(c.result)
        if c.name == "Read" and c.input.get("offset") is None:
            p = norm_path(str(c.input.get("file_path", "")))
            out["full_read_turns"].setdefault(p, c.turn)
        elif c.name == "Bash" and not c.is_error:  # Bash 로 통째 읽기(cat 파일) — 경로는 symbols 에 있는 것만
            for p in bash_cat_paths(str(c.input.get("command", "")), {sp.path for sp in spans}):
                out["full_read_turns"].setdefault(p, c.turn)
        for sp in targeted(c, spans):
            key = next(k2 for k2, v in keys.items() if v is sp)
            out["first_targeted_turn_by_span"].setdefault(key, c.turn)
        is_edit = c.name in EDIT_TOOLS or (c.name == "Bash" and BASH_EDIT.search(str(c.input.get("command", ""))))
        if is_edit and not c.is_error:
            p = norm_path(str(c.input.get("file_path", ""))) if c.name in EDIT_TOOLS else None
            if p and p not in out["edited_paths"]:
                out["edited_paths"].append(p)
            if out["first_edit_turn"] is None:
                out["first_edit_turn"] = c.turn
                out["first_edit_path"] = p
                fixes = [sp for sp in spans if sp.kind == "fix"]
                if c.name in EDIT_TOOLS and fixes:
                    out["first_edit_hit"] = bool(edit_spans(c, fixes))
                # Bash 편집·symbols 없음 = 판정 불가(None)
    if out["first_targeted_turn_by_span"]:
        out["first_targeted_turn"] = min(out["first_targeted_turn_by_span"].values())
    return out


def init_info(evs: list) -> dict:
    """하네스 미로드 확인용: init 이벤트의 로드 항목(사용자 설정이 섞이면 여기 나타난다)."""
    i = next((e for e in evs if e.get("type") == "system" and e.get("subtype") == "init"), {}) or {}
    return {"cli_version": i.get("claude_code_version"), "model": i.get("model"), "cwd": i.get("cwd"),
            "permission_mode": i.get("permissionMode"), "mcp_servers": i.get("mcp_servers"),
            "plugins": [p.get("name") for p in i.get("plugins") or []], "memory_paths": i.get("memory_paths")}
