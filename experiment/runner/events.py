"""stream-json 이벤트 → 지표·감사 (ADR-0005 §5·§6.4). 실행·파일 접근 없는 순수 함수만 둔다.

이벤트 형식(claude 2.1.308 스모크 캡처 `tests/fixtures/`): content 블록마다 assistant 이벤트가 나오고 같은 블록 묶음은
message.id 가 같다. 도구 결과는 user 이벤트의 tool_result 블록(tool_use_id 로 짝). 하위 에이전트 메시지는
parent_tool_use_id 가 있다.
"""
import json
import re
import shlex
from dataclasses import dataclass
from urllib.parse import urlsplit

WORK = "/work"
EDIT_TOOLS = ("Edit", "Write", "MultiEdit", "NotebookEdit")
READ_DEFAULT_LIMIT = 2000  # Read 도구 기본 상한(줄)

# 감사: 도구 *입력*에만 적용한다. 결과(파일 내용)까지 보면 코드 안 문자열 때문에 오탐이 난다(앞 실험).
AUDIT_INPUT = [r"/home/jun", r"clean-code-llm-experiment", r"\boracle\b", r"experiment/", r"study-note",
               r"graph-nav-experiment", r"ccle-runner", r"\.credentials\.json"]
# 웹 도구 + 컨테이너 밖에서 실행되거나(원격 트리거·예약·워크플로) 다른 세션과 통신하는 도구 — 격리·토큰 측정 밖
FORBIDDEN_TOOLS = {"WebFetch", "WebSearch", "RemoteTrigger", "CronCreate", "CronDelete", "CronList", "ScheduleWakeup",
                   "SendMessage", "Workflow", "ListAgents"}
# 외부로 나가는 명령. curl·wget 은 로컬 서비스(앱·가짜 PG·DB) 디버깅에 정당하게 쓰이므로 목적지가 로컬이 아닐 때만.
AUDIT_BASH = [r"(^|[\s;&|(])(nc|ncat|ssh|scp|sftp|rsync|gh|telnet)\s", r"\bgit\s+(clone|fetch|pull|push|ls-remote|remote)\b",
              r"\b(pip3?|npm|npx|apt(-get)?|sdk)\s+(install|i|add)\b"]
# 실험 저장소가 공개 원격(GitHub)에 있으므로 그 호스트 이름은 도구·명령 종류와 무관하게 입력에서 잡는다(가장 현실적인 누출 경로).
AUDIT_REMOTE = re.compile(r"(?i)github\.com|githubusercontent\.com|gitlab\.com|bitbucket\.org")
LOCAL_HOSTS = {"localhost", "127.0.0.1", "0.0.0.0", "db", "::1"}
SHELL_OPS = {";", "&", "&&", "|", "||", "(", ")", "\n"}
REDIRECTS = {">", ">>", "<", "<<", "<<<", "&>", ">&", ">|"}
# curl·wget 에서 값을 받는 옵션(그 다음 토큰은 목적지가 아니다). --url 은 목적지 자체라 따로.
FETCH_VALUE_LONG = {"--output", "--header", "--request", "--data", "--data-raw", "--data-binary", "--data-urlencode",
                    "--json", "--user", "--write-out", "--max-time", "--connect-timeout", "--retry", "--user-agent",
                    "--cookie", "--cookie-jar", "--form", "--config", "--output-document", "--post-data", "--post-file",
                    "--referer", "--proxy", "--resolve", "--cacert", "--cert", "--key", "--range", "--upload-file"}
FETCH_VALUE_SHORT = set("oOXHduwTFAemcbxKrEeiYy")  # 묶은 짧은 옵션(-sX)의 마지막 글자가 이 중이면 다음 토큰이 값


def shell_tokens(cmd: str) -> list:
    """셸 명령을 토큰으로(연산자·리다이렉트 분리, 줄바꿈은 명령 경계). 따옴표가 깨졌으면 공백 분리로 대체."""
    out = []
    for line in cmd.splitlines():
        try:
            lx = shlex.shlex(line, posix=True, punctuation_chars=";&|()<>")
            lx.whitespace_split = True
            lx.commenters = ""
            toks = list(lx)
        except ValueError:
            toks = re.findall(r"[;&|()<>]+|[^\s;&|()<>]+", line)
        out += [re.sub(r"^\d+(?=[<>])", "", t) if re.fullmatch(r"\d*[<>&|]+", t) else t for t in toks] + ["\n"]
    return out


def commands(cmd: str) -> list:
    """[(명령 이름, 인자 목록)] — 연산자에서 끊고, 리다이렉트와 그 대상은 인자에서 뺀다(대상은 ('>', 대상) 항목으로)."""
    out, cur, redir = [], [], []
    toks = shell_tokens(cmd)
    i = 0
    while i < len(toks):
        t = toks[i]
        if t in SHELL_OPS or (re.fullmatch(r"[;&|()]+", t) and t not in REDIRECTS):
            if cur or redir:
                out.append((cur[0] if cur else "", cur[1:], redir))
            cur, redir = [], []
        elif t in REDIRECTS or re.fullmatch(r"[<>&]+", t):
            nxt = toks[i + 1] if i + 1 < len(toks) else ""
            if nxt not in SHELL_OPS:
                redir.append((t, nxt))
                i += 1
        else:
            cur.append(t)
        i += 1
    if cur or redir:
        out.append((cur[0] if cur else "", cur[1:], redir))
    return [(os_base(n), args, r) for n, args, r in out]


def os_base(name: str) -> str:
    return name.rsplit("/", 1)[-1]


def host_of(target: str) -> str:
    """목적지 문자열 → 호스트(소문자). scheme 있으면 urlsplit, 없으면 첫 '/' 앞에서 userinfo·포트·IPv6 괄호를 걷어낸다."""
    t = target.strip()
    if "://" in t:
        try:
            return (urlsplit(t).hostname or t).lower()
        except ValueError:
            return t.lower()
    h = t.split("/", 1)[0].rsplit("@", 1)[-1]
    if h.startswith("[") and "]" in h:
        return h[1:h.index("]")].lower()
    return h.split(":", 1)[0].lower()


def fetch_hosts(cmd: str) -> list:
    """curl·wget 의 목적지 호스트(위치 인자와 --url). `$VAR`(DB_URL·PG_URL 등)·로컬 파일 경로는 판정하지 않는다."""
    out = []
    for name, args, _ in commands(cmd):
        if name not in ("curl", "wget"):
            continue
        skip = False
        for j, t in enumerate(args):
            if skip:
                skip = False
                continue
            if t.startswith("--"):
                opt = t.split("=", 1)[0]
                if opt == "--url":
                    tgt = t.split("=", 1)[1] if "=" in t else (args[j + 1] if j + 1 < len(args) else "")
                    skip = "=" not in t
                    if tgt and not tgt.startswith("$"):
                        out.append(host_of(tgt))
                else:
                    skip = opt in FETCH_VALUE_LONG and "=" not in t
                continue
            if t.startswith("-") and len(t) > 1:
                skip = t[-1] in FETCH_VALUE_SHORT
                continue
            if not t or t.startswith(("$", "/", ".", "~")) or ("://" not in t and "." not in t and ":" not in t):
                continue  # 변수·로컬 경로·호스트가 아닌 낱말(예: 따옴표 밖 JSON 조각이 아님 — 그건 값 옵션이 먹는다)
            out.append(host_of(t))
    return [h for h in out if h]


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
    """총 토큰. result.modelUsage 가 있으면 그 합(하위 에이전트 포함 — result.usage 는 본 대화만), tokens_source=modelUsage.
    없으면(시간 상한 강제 종료로 result 없음, 또는 오류 result 에 modelUsage 없음) assistant 메시지 id 마다 마지막 usage 를
    더한 부분 합(partial_tokens=True, tokens_source=assistant_usage). 부분 합은 **하한**이다: 스트림의 assistant usage 는
    메시지 시작 시점 값이라 output 이 최종값보다 훨씬 작고(스모크 실측 1~5%) 진행 중이던 마지막 메시지는 빠진다 —
    input·cache 계열은 거의 맞아 총합 오차는 스모크에서 1~2%. 분석에서 output 계열은 partial 행에서 쓰지 않는다."""
    res = next((e for e in reversed(evs) if e.get("type") == "result"), None)
    mu = (res or {}).get("modelUsage") or {}
    if mu:
        return {"total_tokens": sum(_usage_sum(v, MU_KEYS) for v in mu.values()),
                "by_model": {m: {k: int(v.get(k) or 0) for k in MU_KEYS} for m, v in mu.items()},
                "cost_usd": res.get("total_cost_usd"), "partial_tokens": False, "tokens_source": "modelUsage"}
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
            "cost_usd": (res or {}).get("total_cost_usd"), "partial_tokens": True, "tokens_source": "assistant_usage"}


# ---------------------------------------------------------------- 종료 원인

LIMIT_TEXT = re.compile(r"usage limit|rate limit|limit reached|hit your limit|limit will reset|quota|overloaded|\b429\b|\b529\b", re.I)


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
        if AUDIT_REMOTE.search(text):
            hits.add("input:remote-host")
        if c.name == "Bash":
            cmd = str(c.input.get("command", ""))
            hits.update(f"bash:{p}" for p in AUDIT_BASH if re.search(p, cmd))
            for host in fetch_hosts(cmd):
                if host not in LOCAL_HOSTS:
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
    if c.is_error:
        return hit
    if c.name == "Read" and (c.input.get("offset") is not None or c.input.get("limit") is not None) \
            and not whole_file_read(c):
        p = norm_path(str(c.input.get("file_path", "")))
        f = (c.meta or {}).get("file") or {}
        if f.get("startLine") is not None and f.get("numLines") is not None:  # 실제로 돌려받은 범위
            s, e = int(f["startLine"]), int(f["startLine"]) + max(int(f["numLines"]), 1) - 1
        else:
            s = int(c.input.get("offset") or 1)
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


def whole_file_read(c: Call) -> bool:
    """Read 가 파일 전체를 돌려받았나: offset 없음, 또는 결과가 1줄부터 파일 끝까지(offset=1 로 전체 읽기 — 포화 우회 차단)."""
    if c.name != "Read":
        return False
    f = (c.meta or {}).get("file") or {}
    if c.input.get("offset") is None and c.input.get("limit") is None:
        return True
    if c.input.get("offset") is None and not f:  # limit 만 있고 결과 범위를 모름 — 앞부분 읽기로 본다
        return False
    try:
        start, num, total = int(f["startLine"]), int(f["numLines"]), int(f["totalLines"])
    except (KeyError, TypeError, ValueError):
        return False
    return start <= 1 and start + num - 1 >= total - 1  # 끝 개행 때문에 totalLines 가 1 클 수 있다


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


def _in_work(path: str) -> bool:
    """쓰기 대상이 작업 사본 안인가(상대 경로 또는 /work/…). /tmp·/dev·HOME 등은 편집이 아니다(로그·버리는 출력)."""
    p = path.strip("'\"")
    if not p or p.startswith(("$", "&")) or p in ("-",) or p.endswith((".log", ".out")):
        return False
    return p.startswith(WORK + "/") or not p.startswith(("/", "~"))


def bash_edit(cmd: str) -> bool:
    """Bash 가 작업 사본 안 파일을 바꿨나: 사본 안 대상으로의 `>`·`>>` 리다이렉트, `tee` 대상, `sed -i` 대상,
    `patch`(--dry-run 제외), `cp`·`mv`의 사본 안 목적지. 판정 불가한 스크립트 실행(python - <<)은 세지 않는다
    — 사본 변경 여부의 정본은 실행 뒤 diff 다."""
    for name, args, redir in commands(cmd):
        if any(op in (">", ">>", ">|", "&>") and _in_work(tgt) for op, tgt in redir):
            return True
        files = [a for a in args if not a.startswith("-")]
        if name == "tee" and any(_in_work(a) for a in files):
            return True
        if name == "sed" and any(a == "-i" or a.startswith("-i") or a == "--in-place" for a in args) and \
                any(_in_work(a) for a in files[1:]):
            return True
        if name == "patch" and "--dry-run" not in args:
            return True
        if name in ("cp", "mv") and len(files) >= 2 and _in_work(files[-1]):
            return True
    return False


def metrics(cs: list, spans: list) -> dict:
    out = {"tool_calls": len(cs), "tools": {}, "subagent_calls": 0,
           "lines_read": {"read": 0, "grep": 0, "bash": 0, "other": 0, "sub": 0},
           "first_targeted_turn": None, "first_targeted_turn_by_span": {}, "full_read_turns": {},
           "first_edit_turn": None, "first_edit_tool_turn": None, "first_edit_path": None, "first_edit_hit": None,
           "edited_paths": []}
    keys = {f"{sp.path}:{sp.start}-{sp.end}": sp for sp in spans}
    for c in cs:
        out["tools"][c.name] = out["tools"].get(c.name, 0) + 1
        if c.name in ("Agent", "Task"):
            out["subagent_calls"] += 1
        if c.name not in EDIT_TOOLS:  # 편집 결과 스니펫은 읽기가 아니다. 하위 에이전트 안의 읽기는 sub 로 따로
            k = "sub" if c.sub else {"Read": "read", "Grep": "grep", "Bash": "bash"}.get(c.name, "other")
            out["lines_read"][k] += _lines(c.result)
        if whole_file_read(c) and not c.is_error:
            p = norm_path(str(c.input.get("file_path", "")))
            out["full_read_turns"].setdefault(p, c.turn)
        elif c.name == "Bash" and not c.is_error:  # Bash 로 통째 읽기(cat 파일) — 경로는 symbols 에 있는 것만
            for p in bash_cat_paths(str(c.input.get("command", "")), {sp.path for sp in spans}):
                out["full_read_turns"].setdefault(p, c.turn)
        for sp in targeted(c, spans):
            key = next(k2 for k2, v in keys.items() if v is sp)
            out["first_targeted_turn_by_span"].setdefault(key, c.turn)
        if c.is_error:
            continue
        if c.name in EDIT_TOOLS:
            p = norm_path(str(c.input.get("file_path", "")))
            if p and p not in out["edited_paths"]:
                out["edited_paths"].append(p)
            if out["first_edit_tool_turn"] is None:  # ADR §5.2 첫 편집 적중 = 첫 Edit 계열 호출 기준(앞선 Bash 편집과 무관)
                out["first_edit_tool_turn"] = c.turn
                out["first_edit_path"] = p
                fixes = [sp for sp in spans if sp.kind == "fix"]
                out["first_edit_hit"] = bool(edit_spans(c, fixes)) if fixes else None
        if out["first_edit_turn"] is None and (c.name in EDIT_TOOLS or
                                               (c.name == "Bash" and bash_edit(str(c.input.get("command", ""))))):
            out["first_edit_turn"] = c.turn  # 편집 수단과 무관한 첫 편집(Bash 포함)
    if out["first_targeted_turn_by_span"]:
        out["first_targeted_turn"] = min(out["first_targeted_turn_by_span"].values())
    return out


def result_count(evs: list) -> int:
    return sum(1 for e in evs if e.get("type") == "result")


BUILTIN_PLUGINS = {"cc-plugin-agents-md", "cc-plugin-plugin-authoring"}  # 2.1.308 내장(스모크 init 실측)


def harness_problems(info: dict) -> list:
    """하네스 미로드(spec §2) 자동 판정: MCP 서버 없음 · 플러그인은 내장만 · 메모리 경로는 격리 HOME 안 · cwd=/work."""
    bad = []
    if info.get("mcp_servers"):
        bad.append(f"mcp_servers={info['mcp_servers']}")
    extra = sorted(set(info.get("plugins") or []) - BUILTIN_PLUGINS)
    if extra:
        bad.append(f"plugins={extra}")
    for k, v in (info.get("memory_paths") or {}).items():
        if not str(v).startswith("/home/agent/"):
            bad.append(f"memory_paths.{k}={v}")
    if info.get("cwd") not in (None, WORK):
        bad.append(f"cwd={info.get('cwd')}")
    return bad


def init_info(evs: list) -> dict:
    """하네스 미로드 확인용: init 이벤트의 로드 항목(사용자 설정이 섞이면 여기 나타난다)."""
    i = next((e for e in evs if e.get("type") == "system" and e.get("subtype") == "init"), {}) or {}
    return {"cli_version": i.get("claude_code_version"), "model": i.get("model"), "cwd": i.get("cwd"),
            "permission_mode": i.get("permissionMode"), "mcp_servers": i.get("mcp_servers"),
            "plugins": [p.get("name") for p in i.get("plugins") or []], "memory_paths": i.get("memory_paths")}
