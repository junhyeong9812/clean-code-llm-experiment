"""stream-json 이벤트 → 지표·감사 (ADR-0005 §5·§6.4). 실행·파일 접근 없는 순수 함수만 둔다.

이벤트 형식(claude 2.1.308 스모크 캡처 `tests/fixtures/`): content 블록마다 assistant 이벤트가 나오고 같은 블록 묶음은
message.id 가 같다. 도구 결과는 user 이벤트의 tool_result 블록(tool_use_id 로 짝). 하위 에이전트 메시지는
parent_tool_use_id 가 있다.
"""
import ast
import itertools
import json
import posixpath
import re
import shlex
from dataclasses import dataclass
from urllib.parse import urlsplit

WORK = "/work"
EDIT_TOOLS = ("Edit", "Write", "MultiEdit", "NotebookEdit")
READ_DEFAULT_LIMIT = 2000  # Read 도구 기본 상한(줄)
# 겨냥 Read 폭 상한(줄, 사용자 결정 2026-10-26): 이보다 넓게 돌려받은 Read 는 span 을 덮어도 겨냥이 아니라 청크 읽기 —
# god 의 큰 파일을 나눠 읽기만 해도 겨냥으로 포화되는 것을 막는다(layered 는 파일이 작아 구조에 비대칭으로 걸린다)
READ_TARGET_MAX = 400

# 감사: 도구 *입력*에만 적용한다. 결과(파일 내용)까지 보면 코드 안 문자열 때문에 오탐이 난다(앞 실험).
AUDIT_INPUT = [r"/home/jun", r"clean-code-llm-experiment", r"\boracle\b", r"experiment/", r"study-note",
               r"graph-nav-experiment", r"ccle-runner", r"\.credentials\.json"]
# 웹 도구 + 컨테이너 밖에서 실행되거나(원격 트리거·예약·워크플로) 다른 세션과 통신하는 도구 — 격리·토큰 측정 밖
FORBIDDEN_TOOLS = {"WebFetch", "WebSearch", "RemoteTrigger", "CronCreate", "CronDelete", "CronList", "ScheduleWakeup",
                   "SendMessage", "Workflow", "ListAgents"}
# 외부로 나가는 명령. curl·wget 은 로컬 서비스(앱·가짜 PG·DB) 디버깅에 정당하게 쓰이므로 목적지가 로컬이 아닐 때만.
# 외부 접속 통제의 정본은 허용 목록 프록시(runner — 에이전트는 --internal 네트워크)다. 이 감사는 시도를 드러내는 보조.
NET_CMDS = {"nc", "ncat", "ssh", "scp", "sftp", "rsync", "gh", "telnet"}
GIT_NET = {"clone", "fetch", "pull", "push", "ls-remote", "remote"}
PKG_CMDS = {"pip", "pip3", "npm", "npx", "apt", "apt-get", "sdk"}
CONTENT_FIELDS = {"content", "new_string", "old_string", "edits", "new_source"}
NET_VALUE_OPTS = {"-w", "-p", "-s", "-i", "-q", "-x", "-X", "-W", "-T", "-O", "-I", "-e", "-c", "-b", "-l"} - {"-l"}
WRAPPER_VALUE_OPTS = {"-u", "-g", "-n", "-s", "-k", "-o", "-e", "-i", "-L", "-C", "--signal", "--kill-after", "--user"}
WRAPPERS = {"timeout", "env", "nice", "nohup", "xargs", "sudo", "time", "command", "exec", "stdbuf"}
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


def net_target_external(name: str, args: list) -> bool:
    """nc·telnet 은 첫 위치 인자 호스트가 로컬이 아니면, rsync·scp 는 원격 지정(host:path)이 있으면, 나머지는 항상."""
    pos, skip = [], False
    for x in args:  # 값을 받는 옵션(nc -w 2·-p 1234·-s addr 등)의 값은 위치 인자가 아니다
        if skip:
            skip = False
        elif x.startswith("-"):
            skip = x in NET_VALUE_OPTS
        else:
            pos.append(x)
    if name in ("nc", "ncat", "telnet"):
        return bool(pos) and host_of(pos[0]) not in LOCAL_HOSTS
    if name in ("rsync", "scp"):
        return any(re.match(r"^[^/\s]+:", x) and host_of(x.split(":", 1)[0]) not in LOCAL_HOSTS for x in pos)
    return True


def unwrap(cmds: list, depth: int = 0) -> list:
    """`timeout 30 curl …`·`env A=1 curl …`·`sh -c "…"` 를 벗겨 실제 명령을 드러낸다(재귀 2단계)."""
    out = []
    for name, args, redir in cmds:
        while name in WRAPPERS and args:
            rest, skip = [], False
            for a in args:  # 래퍼 옵션 값(sudo -u root·nice -n 5·timeout -s KILL)은 명령이 아니다
                if skip:
                    skip = False
                elif a.startswith("-"):
                    skip = a in WRAPPER_VALUE_OPTS
                elif "=" not in a or rest:
                    rest.append(a)
            while rest and re.fullmatch(r"[\d.]+[smhd]?|[A-Z]+", rest[0]):  # 옵션 값(timeout 30·nice -n 5·-s KILL)
                rest = rest[1:]
            if not rest:
                break
            i = args.index(rest[0])
            name, args = os_base(rest[0]), args[i + 1:]
        if name in ("sh", "bash", "zsh", "dash") and "-c" in args and depth < 2:
            j = args.index("-c")
            if j + 1 < len(args):
                out += unwrap(commands(args[j + 1]), depth + 1)
                continue
        out.append((name, args, redir))
    return out


def fetch_hosts(cmd: str) -> list:
    """curl·wget 의 목적지 호스트(위치 인자와 --url). `$VAR`(DB_URL·PG_URL 등)·로컬 파일 경로는 판정하지 않는다."""
    out = []
    for name, args, _ in unwrap(commands(cmd)):
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
        # 편집·쓰기 도구의 내용(new_string·content)은 결과와 같은 성격(코드 문자열) — 경로 필드만 본다
        fields = {k: v for k, v in c.input.items() if k not in CONTENT_FIELDS}
        text = json.dumps(fields, ensure_ascii=False)
        hits.update(f"input:{p}" for p in AUDIT_INPUT if re.search(p, text))
        if AUDIT_REMOTE.search(text):
            hits.add("input:remote-host")
        if c.name == "Bash":
            cmd = str(c.input.get("command", ""))
            for name, args, _ in unwrap(commands(cmd)):
                if name in NET_CMDS and net_target_external(name, args):
                    hits.add(f"bash:{name}")
                elif name == "git" and args and args[0] in GIT_NET:
                    hits.add(f"bash:git {args[0]}")
                elif name in PKG_CMDS and args and args[0] in ("install", "i", "add"):
                    hits.add(f"bash:{name} {args[0]}")
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
    """oracle symbols.<구조>.txt: 줄마다 `경로:시작-끝 fix|read|edit [메모]`(경로는 사본 루트 기준). # 주석·빈 줄 무시.
    edit = 첫 편집 적중 전용(파일 단위 같은 넓은 범위 — 겨냥 판정에는 쓰지 않는다)."""
    out = []
    for raw in text.splitlines():
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        m = re.fullmatch(r"(\S+):(\d+)(?:-(\d+))?\s+(fix|read|edit)\b.*", line)
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


READ_LINE_NO = re.compile(r"^\s*(\d+)[\t→]", re.M)


def read_range(c: Call):
    """Read 가 돌려받은 (시작, 끝) 줄: ① 구조화 결과(file.startLine·numLines) ② 결과 텍스트의 줄 번호(`N\t…`)
    ③ 그래도 모르면 입력 offset·limit(요청 폭 — 실제보다 넓을 수 있다)."""
    f = (c.meta or {}).get("file") or {}
    if f.get("startLine") is not None and f.get("numLines") is not None:
        return int(f["startLine"]), int(f["startLine"]) + max(int(f["numLines"]), 1) - 1
    ns = [int(x) for x in READ_LINE_NO.findall(c.result or "")]
    if ns and ns[0] == int(c.input.get("offset") or 1):  # 실제 출력은 offset 줄부터 — 어긋나면 텍스트를 믿지 않는다
        return ns[0], max(ns)
    s = int(c.input.get("offset") or 1)
    return s, s + int(c.input.get("limit") or READ_DEFAULT_LIMIT) - 1


def chunk_read(c: Call) -> bool:
    """offset 을 준 Read 지만 폭이 READ_TARGET_MAX 를 넘는다(파일 전체 읽기는 아님)."""
    if c.name != "Read" or c.is_error or c.input.get("offset") is None or whole_file_read(c):
        return False
    s, e = read_range(c)
    return e - s + 1 > READ_TARGET_MAX


def targeted(c: Call, spans: list, cwd: str = WORK) -> list:
    """이 호출이 겨냥한 span 목록(ADR §5.2):
    - Read: offset 을 준 Read 가 span 을 **전부 덮고** 돌려받은 폭이 READ_TARGET_MAX 이하. 더 넓으면 청크 읽기(따로 기록),
      offset 없는 Read·파일 전체를 돌려받은 Read 는 전체 읽기(따로 기록).
    - Grep·Bash grep: 결과 줄이 span 안. Edit: 편집한 원래 줄 범위가 span 과 겹침.
    cwd = 이 Bash 호출이 시작될 때의 셸 작업 위치(앞 호출의 `cd` — metrics 가 추적)."""
    hit = []
    if c.is_error:
        return hit
    if c.name == "Read" and c.input.get("offset") is not None and not whole_file_read(c) and not chunk_read(c):
        p = norm_path(str(c.input.get("file_path", "")))
        s, e = read_range(c)
        hit = [sp for sp in spans if sp.path == p and s <= sp.start and sp.end <= e]
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
        hit = bash_spans(c, spans, cwd)
    return hit


# ---------------------------------------------------------------- Bash 명령 해석(작업 위치 · 읽기 · 쓰기 대상)
# 파일럿(과제 A) 실측: 에이전트는 Read·Edit 대신 Bash 만 썼다 — `cd app/src/…` 뒤 상대 경로로 cat·sed -n·grep,
# 편집은 `sed -i` 와 python heredoc(open(p,'w')). 경로는 그 조각이 돌 때의 작업 위치로 푼다.

HEREDOC = re.compile(r"(?<!<)<<(-?)\s*(['\"]?)([A-Za-z_]\w*)\2")
HD_MARK = re.compile(r"__HD(\d+)__")


@dataclass
class Seg:
    name: str
    args: list
    redir: list
    body: str  # 이 명령에 걸린 heredoc 본문(없으면 "")
    cwd: str


def split_heredocs(cmd: str) -> list:
    """[(명령 줄, [본문…])] — heredoc 본문을 셸 명령으로 파싱하지 않는다(본문 안 `>` 가 리다이렉트로 오탐되지 않게).
    줄의 heredoc 연산자는 `<<__HD<k>__` 표지로 바꿔 어느 명령의 본문인지 남긴다."""
    out, lines, i = [], cmd.splitlines(), 0
    while i < len(lines):
        line, bodies = lines[i], []
        i += 1
        for m in HEREDOC.finditer(line):
            body = []
            while i < len(lines) and (lines[i].lstrip("\t") if m[1] else lines[i]) != m[3]:
                body.append(lines[i])
                i += 1
            i += 1
            bodies.append("\n".join(body))
        k = iter(range(len(bodies)))
        out.append((HEREDOC.sub(lambda _: f" <<__HD{next(k)}__ ", line), bodies))
    return out


def resolve(cwd: str, p: str) -> str:
    """셸 작업 위치 기준 경로 → 사본 기준 상대 경로(norm_path). `~`·`$` 로 시작하면 풀지 않는다."""
    p = p.strip("'\"")
    if not p or p.startswith(("~", "$")):
        return p
    return norm_path(posixpath.normpath(posixpath.join(cwd, p)))


def in_work(cwd: str) -> bool:
    return cwd == WORK or cwd.startswith(WORK + "/")


def segments(cmd: str, cwd: str = WORK) -> tuple:
    """(명령 조각 목록, 호출이 끝난 뒤 작업 위치). `cd`·`pushd` 는 뒤 조각과 다음 Bash 호출의 작업 위치를 바꾼다(Bash 도구는
    호출 사이에 작업 위치를 유지하고, 사본 밖으로 나가면 사본 루트로 되돌린다). `cd -`·변수 경로는 풀지 않는다."""
    out = []
    for line, bodies in split_heredocs(cmd):
        for name, args, redir in unwrap(commands(line)):
            if name in ("cd", "pushd"):
                d = next((a for a in args if not a.startswith("-")), None)
                if d is None:
                    cwd = "~" if not args else cwd
                elif not d.startswith(("$", "~")):
                    cwd = posixpath.normpath(posixpath.join(cwd, d))
                continue
            body = "\n".join(bodies[int(m[1])] for op, t in redir if op == "<<" for m in [HD_MARK.fullmatch(t)] if m)
            out.append(Seg(name, args, [(op, t) for op, t in redir if op != "<<"], body, cwd))
    return out, (cwd if in_work(cwd) else WORK)


def _pos(args: list) -> list:
    return [a for a in args if not a.startswith("-")]


SED_PRINT = re.compile(r"(\d+)(?:,(\d+))?p")


def sed_reads(seg: Seg) -> list:
    """`sed -n 'A,Bp' 파일…` → [(경로, A, B)]. 제자리 편집(-i)은 읽기가 아니다."""
    if seg.name != "sed" or "-n" not in seg.args or _sed_inplace(seg.args):
        return []
    pos = _pos(seg.args)
    m = SED_PRINT.fullmatch(pos[0]) if pos else None
    return [(resolve(seg.cwd, f), int(m[1]), int(m[2] or m[1])) for f in pos[1:]] if m else []


def bash_cat_paths(segs: list, paths: set) -> list:
    out = []
    for s in segs:
        if s.name == "cat":
            for a in _pos(s.args):
                p = resolve(s.cwd, a)
                if p in paths and p not in out:
                    out.append(p)
    return out


# Bash 로 읽기(이 CLI 빌드에는 Grep 도구가 없다 — 스모크 캡처): grep/rg -n 결과 줄 · sed -n 'A,Bp' 파일
BASH_GREP_LINE = re.compile(r"^(?:(?P<path>[^\s:]+?)[:-])?(?P<line>\d+)[:-]", re.M)


def bash_spans(c: Call, spans: list, cwd: str = WORK) -> list:
    segs, _ = segments(str(c.input.get("command", "")), cwd)
    paths = {sp.path for sp in spans}
    hit = []

    def add(p, s, e):
        for sp in spans:
            if sp.path == p and _overlap(s, e, sp.start, sp.end) and sp not in hit:
                hit.append(sp)

    for seg in segs:  # Read 와 같은 규칙: span 을 전부 덮고 폭 ≤ READ_TARGET_MAX(넓으면 청크 — metrics)
        for p, s, e in sed_reads(seg):
            if e - s + 1 <= READ_TARGET_MAX:
                hit += [sp for sp in spans if sp.path == p and s <= sp.start and sp.end <= e and sp not in hit]
    greps = [s for s in segs if s.name in ("grep", "egrep", "rg")]
    if greps:
        named = []  # grep 에 이름이 나온 span 파일 — 경로 없는 결과 줄("12:…")의 주인. 파일 인자가 없는 grep(표준 입력)은
        for i, s in enumerate(segs):  # 바로 앞 조각(파이프 앞 cat 등)의 파일
            if s.name not in ("grep", "egrep", "rg"):
                continue
            src = s if len(_pos(s.args)) > 1 else (segs[i - 1] if i else None)
            for a in _pos(src.args) if src else []:
                p = resolve(src.cwd, a)
                if p in paths and p not in named:
                    named.append(p)
        bases = list(dict.fromkeys(s.cwd for s in greps))
        for m in BASH_GREP_LINE.finditer(c.result):
            n = int(m["line"])
            if m["path"]:
                for b in bases:  # 결과 경로는 grep 이 돈 위치 기준
                    add(resolve(b, m["path"]), n, n)
            elif len(named) == 1:
                add(named[0], n, n)
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


def _work_target(rel: str) -> bool:
    """resolve 뒤 경로가 사본 안 편집 대상인가. /tmp·/dev·HOME·변수·`-`·로그(.log·.out)는 편집이 아니다(버리는 출력)."""
    return bool(rel) and not rel.startswith(("/", "~", "$", "&")) and rel != "-" and not rel.endswith((".log", ".out"))


def _sed_inplace(args: list) -> bool:
    return any(a == "--in-place" or a.startswith("--in-place=") or (a.startswith("-") and not a.startswith("--") and "i" in a)
               for a in args)


def _files_after_script(args: list, value_opts: set) -> list:
    """sed·perl 의 대상 파일: -e/-f 로 스크립트를 주면 위치 인자 전부, 아니면 첫 위치 인자(스크립트) 뒤."""
    files, given, skip = [], False, False
    for a in args:
        if skip:
            skip = False
        elif a.split("=", 1)[0] in value_opts:
            given, skip = True, "=" not in a
        elif not a.startswith("-"):
            files.append(a)
    return files if given else files[1:]


DIFF_TARGET = re.compile(r"^\+\+\+ (\S+)", re.M)


def _patch_targets(seg: Seg, default_strip) -> list:
    """heredoc 본문의 `+++ 경로` 에서 대상(-pN 만큼 앞 디렉터리를 떼고, -p 가 없으면 patch 는 파일 이름만). 본문이 없으면 None."""
    if not seg.body:
        return None
    n = default_strip
    for i, a in enumerate(seg.args):
        if a.startswith("-p") and a[2:].isdigit():
            n = int(a[2:])
        elif a == "-p" and i + 1 < len(seg.args) and seg.args[i + 1].isdigit():
            n = int(seg.args[i + 1])
    out = []
    for t in DIFF_TARGET.findall(seg.body):
        if t != "/dev/null":
            out.append(posixpath.basename(t) if n is None else "/".join(t.split("/")[n:]))
    return out


PY_SITE_FUNCS = {("shutil", "copy"), ("shutil", "copy2"), ("shutil", "copyfile"), ("shutil", "move"), ("os", "replace"),
                 ("os", "rename")}


def py_write_targets(src: str) -> tuple:
    """python 스크립트의 쓰기 대상 경로 문자열과 '모름' 여부. 쓰기 = open(…, 'w'·'a'·'x'·'+') · write_text·write_bytes ·
    shutil.copy*/move · os.replace/rename 의 대상 인자. 대상은 리터럴 · Path()/os.path.join/`/` 조합 · 변수(그 이름에 대입된
    값 **전부** — 흐름 무시, 넓게 잡는다) · for 리터럴 목록 · 함수 매개변수(호출처 인자)로 푼다. 하나라도 못 풀면 모름."""
    try:
        tree = ast.parse(src)
    except SyntaxError:
        return [], bool(re.search(r"\bopen\(|write_text|write_bytes|shutil\.|os\.(?:replace|rename)", src))
    vals, params, calls, sites, unknown = {}, {}, [], [], False
    for n in ast.walk(tree):
        if isinstance(n, ast.Assign):
            for t in n.targets:
                if isinstance(t, ast.Name):
                    vals.setdefault(t.id, []).append(n.value)
        elif isinstance(n, ast.For) and isinstance(n.target, ast.Name) and isinstance(n.iter, (ast.List, ast.Tuple)):
            vals.setdefault(n.target.id, []).extend(n.iter.elts)
        elif isinstance(n, ast.FunctionDef):
            for k, a in enumerate(n.args.args):
                params[a.arg] = (n.name, k)
        elif isinstance(n, ast.Call):
            calls.append(n)
            f = n.func
            name = f.id if isinstance(f, ast.Name) else f.attr if isinstance(f, ast.Attribute) else ""
            mod = f.value.id if isinstance(f, ast.Attribute) and isinstance(f.value, ast.Name) else None
            if name == "open" and n.args:
                mode = n.args[1] if len(n.args) > 1 else next((k.value for k in n.keywords if k.arg == "mode"), None)
                if isinstance(mode, ast.Constant) and isinstance(mode.value, str):
                    if re.search(r"[wax+]", mode.value):
                        sites.append(n.args[0])
                elif mode is not None:
                    unknown = True
            elif name in ("write_text", "write_bytes") and isinstance(f, ast.Attribute):
                sites.append(f.value)
            elif (mod, name) in PY_SITE_FUNCS and len(n.args) >= 2:
                sites.append(n.args[1])
            elif (mod, name) == ("os", "chdir"):
                unknown = True  # 상대 경로의 기준이 바뀐다

    def strs(e, depth=0):
        if e is None or depth > 6:
            return None
        if isinstance(e, ast.Constant) and isinstance(e.value, str):
            return [e.value]
        if isinstance(e, ast.Call) and e.args:
            fn = e.func.id if isinstance(e.func, ast.Name) else e.func.attr if isinstance(e.func, ast.Attribute) else ""
            if fn in ("Path", "PurePath", "PosixPath", "join"):
                parts = [strs(a, depth + 1) for a in e.args]
                return None if None in parts else [posixpath.join(*x) for x in itertools.product(*parts)]
            return None
        if isinstance(e, ast.BinOp) and isinstance(e.op, ast.Div):
            l, r = strs(e.left, depth + 1), strs(e.right, depth + 1)
            return None if l is None or r is None else [posixpath.join(x, y) for x in l for y in r]
        if isinstance(e, ast.Name) and e.id in vals:
            got = [strs(v, depth + 1) for v in vals[e.id]]
            return None if None in got else [x for g in got for x in g]
        if isinstance(e, ast.Name) and e.id in params:
            fn, k = params[e.id]
            got = []
            for c in calls:
                if isinstance(c.func, ast.Name) and c.func.id == fn:
                    arg = c.args[k] if len(c.args) > k else next((kw.value for kw in c.keywords if kw.arg == e.id), None)
                    got.append(strs(arg, depth + 1))
            return None if not got or None in got else [x for g in got for x in g]
        return None

    out = []
    for s in sites:
        r = strs(s)
        if r is None:
            unknown = True
        else:
            out += [x for x in r if x not in out]
    return out, unknown


SCRIPT_WRITE_HINT = re.compile(r"writeFile|appendFile|createWriteStream|File\.write|File\.open|\bopen\s*\(|>\s*\$")


def bash_writes(segs: list) -> tuple:
    """(사본 안 쓰기 대상 — 사본 기준 상대 경로, 대상을 모르는 쓰기 명령 이름 목록). ADR §5.2 Bash 편집:
    `>`·`>>`·`&>` 리다이렉트 · tee · sed -i · perl -i · cp/mv/install 목적지 · patch·git apply(heredoc 본문의 +++ 대상) ·
    python(-c·heredoc 본문의 파일 쓰기 — py_write_targets). 입력 파일을 볼 수 없는 patch, 내용을 볼 수 없는 스크립트 파일
    실행(python x.py), 쓰기 흔적이 있는 다른 인터프리터 인라인 코드는 '모름'으로 남긴다."""
    out, unknown = [], []

    def add(cwd, p):
        r = resolve(cwd, p)
        if _work_target(r) and r not in out:
            out.append(r)

    for s in segs:
        for op, t in s.redir:
            if op in (">", ">>", ">|", "&>", "&>>") and not t.startswith("&"):
                add(s.cwd, t)
        pos = _pos(s.args)
        if s.name == "tee":
            for f in pos:
                add(s.cwd, f)
        elif s.name == "sed" and _sed_inplace(s.args):
            for f in _files_after_script(s.args, {"-e", "-f", "--expression", "--file"}):
                add(s.cwd, f)
        elif s.name == "perl" and any(a.startswith("-") and not a.startswith("--") and "i" in a for a in s.args):
            for f in _files_after_script(s.args, {"-e", "-E"}):
                add(s.cwd, f)
        elif s.name in ("cp", "mv", "install") and len(pos) >= 2:
            dst = pos[-1]
            if dst.endswith("/") or dst in (".", "..") or "." not in posixpath.basename(dst):
                for src in pos[:-1]:
                    add(s.cwd, posixpath.join(dst, posixpath.basename(src.rstrip("/"))))
            else:
                add(s.cwd, dst)
        elif (s.name == "patch" and "--dry-run" not in s.args) or \
                (s.name == "git" and s.args[:1] == ["apply"] and not {"--check", "--stat", "--numstat"} & set(s.args)):
            tg = _patch_targets(s, None if s.name == "patch" else 1)
            if tg is None and s.name == "patch" and pos:
                tg = [pos[0]]  # patch 원본파일 [패치파일]
            if tg is None:
                unknown.append(s.name)
            for f in tg or []:
                add(s.cwd, f)
        elif s.name.startswith("python"):
            src = None
            if "-c" in s.args and s.args.index("-c") + 1 < len(s.args):
                src = s.args[s.args.index("-c") + 1]
            elif s.body:
                src = s.body
            elif pos and pos[0].endswith(".py"):
                unknown.append(s.name)  # 스크립트 파일 내용은 이벤트에 없다
            if src is not None:
                tg, unk = py_write_targets(src)
                for f in tg:
                    add(s.cwd, f)
                if unk:
                    unknown.append(s.name)
        elif s.name in ("node", "ruby", "perl", "php") and \
                SCRIPT_WRITE_HINT.search(s.body + " " + " ".join(s.args)):
            unknown.append(s.name)
    return out, unknown


def bash_edit(cmd: str) -> bool:
    """Bash 가 작업 사본 안 파일을 바꿨나(대상을 아는 쓰기만 — bash_writes)."""
    return bool(bash_writes(segments(cmd)[0])[0])


def metrics(cs: list, spans: list, existing=None) -> dict:
    """existing = 실행 전 사본의 파일 목록(사본 기준 상대 경로). Bash 쓰기가 새 파일을 만든 것인지 가르는 데만 쓴다 —
    None 이면 Bash 쓰기 대상을 모두 기존 파일 수정으로 본다."""
    out = {"tool_calls": len(cs), "tools": {}, "subagent_calls": 0,
           "lines_read": {"read": 0, "grep": 0, "bash": 0, "other": 0, "sub": 0},
           "first_targeted_turn": None, "first_targeted_turn_by_span": {}, "full_read_turns": {}, "chunk_read_turns": {},
           "first_edit_turn": None, "first_edit_tool_turn": None, "first_edit_path": None, "first_edit_hit": None,
           "first_edit_via": None, "edited_paths": [], "created_paths": [], "edit_unknown_turns": []}
    keys = {f"{sp.path}:{sp.start}-{sp.end}": sp for sp in spans}
    paths = {sp.path for sp in spans}
    fixes = [sp for sp in spans if sp.kind in ("fix", "edit")]
    cwd = WORK  # Bash 셸 작업 위치(호출 사이에 유지 — segments)
    for c in cs:
        segs, after = segments(str(c.input.get("command", "")), cwd) if c.name == "Bash" else ([], cwd)
        out["tools"][c.name] = out["tools"].get(c.name, 0) + 1
        if c.name in ("Agent", "Task"):
            out["subagent_calls"] += 1
        if c.name not in EDIT_TOOLS:  # 편집 결과 스니펫은 읽기가 아니다. 하위 에이전트 안의 읽기는 sub 로 따로
            k = "sub" if c.sub else {"Read": "read", "Grep": "grep", "Bash": "bash"}.get(c.name, "other")
            out["lines_read"][k] += _lines(c.result)
        if whole_file_read(c) and not c.is_error:
            p = norm_path(str(c.input.get("file_path", "")))
            out["full_read_turns"].setdefault(p, c.turn)
        elif chunk_read(c):
            out["chunk_read_turns"].setdefault(norm_path(str(c.input.get("file_path", ""))), c.turn)
        if c.name == "Bash" and not c.is_error:
            for seg in segs:
                for p, s, e in sed_reads(seg):
                    if e - s + 1 > READ_TARGET_MAX:
                        out["chunk_read_turns"].setdefault(p, c.turn)
            for p in bash_cat_paths(segs, paths):  # Bash 로 통째 읽기(cat 파일) — 경로는 symbols 에 있는 것만
                out["full_read_turns"].setdefault(p, c.turn)
        for sp in targeted(c, [x for x in spans if x.kind != "edit"], cwd):
            key = next(k2 for k2, v in keys.items() if v is sp)
            out["first_targeted_turn_by_span"].setdefault(key, c.turn)
        cwd = after
        if c.is_error:
            continue
        wrote = False
        if c.name in EDIT_TOOLS:
            wrote = True
            p = norm_path(str(c.input.get("file_path", "")))
            if p and p not in out["edited_paths"]:
                out["edited_paths"].append(p)
            creates = (c.meta or {}).get("type") == "create" or (c.name == "Write" and (c.meta or {}).get("originalFile") is None
                                                                and c.meta is not None)
            if creates:
                out["created_paths"].append(p)
            elif out["first_edit_tool_turn"] is None:  # ADR §5.2 첫 편집 적중 = 기존 파일을 고친 첫 편집 호출(새 파일 생성 제외)
                out.update(first_edit_tool_turn=c.turn, first_edit_path=p, first_edit_via=c.name)
                out["first_edit_hit"] = bool(edit_spans(c, fixes)) if fixes else None
        elif c.name == "Bash":
            tg, unk = bash_writes(segs)
            if unk and c.turn not in out["edit_unknown_turns"]:
                out["edit_unknown_turns"].append(c.turn)
            for p in tg:
                if p not in out["edited_paths"]:
                    out["edited_paths"].append(p)
                if existing is not None and p not in existing and p not in out["created_paths"]:
                    out["created_paths"].append(p)
            mods = [p for p in tg if existing is None or p in existing]
            if mods and out["first_edit_tool_turn"] is None:  # Bash 편집은 줄 범위를 모른다 — 적중은 파일 단위(symbols 경로)
                out.update(first_edit_tool_turn=c.turn, first_edit_path=mods[0], first_edit_via="Bash")
                out["first_edit_hit"] = any(p in {sp.path for sp in fixes} for p in mods) if fixes else None
            wrote = bool(tg) or any(u in ("patch", "git") for u in unk)
        if out["first_edit_turn"] is None and wrote:
            out["first_edit_turn"] = c.turn  # 편집 수단과 무관한 첫 편집(새 파일 생성 포함)
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
