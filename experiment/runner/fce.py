#!/usr/bin/env python3
"""첫 정답 편집 턴 추출기 (ADR-0008 §5.1~§5.5).

실행 뒤 raw 이벤트의 **본 대화** 도구 호출을 기준 사본(실행 시작 시점 /work)에서 순서대로 재생하고, 호출 전후로
기준 사본에 있던 파일의 내용이 실제로 바뀐 호출(편집 사건)을 찾는다. 바뀐 줄은 기준 좌표로 되돌려(누적 줄 대응)
정답 범위(fix ∪ alt — oracle 패치에서 직접 계산)와 겹치는지 본다. 러너는 바꾸지 않는다(ADR-0008 §5.4).

사용:
  python3 experiment/runner/fce.py run experiment/results-pilot2 --rev '*=ae6deda' > p2.jsonl
  python3 experiment/runner/fce.py run RESULTS --rev S1=abc1234 --rev S3=abc1234 --groups S3=groups.json --table
  python3 experiment/runner/fce.py summary p2.jsonl p3.jsonl          # 실행별 표 + 전체·구조별 중앙값

--rev CASE=REV: 그 과제의 기준 사본(그 리비전의 runner.build_work — 구조 src 복사 + inject 패치)과 판정 범위(그 리비전의
  oracle/<CASE>/fix.<s>.patch·alt-N.<s>.patch)를 만들 리비전. `*=REV` 는 나머지 과제 전부.
--groups CASE=PATH: S3 의 고칠 곳 그룹 정의(§5.3). PATH 는 로컬 파일 또는 `REV:repo/경로`(git show). 형식(JSON):
    {"god": {"<그룹 이름>": ["app/src/…/Marketplace.kt:281-283", …], …}, "layered": {…}}
  범위는 기준 좌표. 그룹 g 도달 = 바뀐 줄이 (fix ∪ alt) ∩ g 의 범위와 겹치는 편집 사건. alt 범위는 그것이 대신하는 그룹의
  범위 목록에 함께 적는다(§5.3). 모든 그룹에 도달한 턴 = all_fix_touched_turn(실패 실행은 +inf). 성공했는데 닿지 않은 그룹이 있으면
  마지막 편집 사건 턴과 all_fix_fallback=true(§5.3 개정 1).

출력(run, JSON lines — 한 실행 1행). 정수 턴 · "+inf"(실패 실행 — §5.1) · null(해당 없음/모름):
  first_correct_edit_turn  주 지표(§5.1). 성공 + 정답 편집 없음이면 첫 편집 사건 턴과 fce_fallback=true
  primary                  과제의 주 지표 — groups 가 있는 과제는 all_fix_touched_turn, 아니면 first_correct_edit_turn
  unknown / unknown_reasons  재생 불가 호출 또는 재생 최종 트리 ≠ 기준 + 기록 diff(§5.1 '모름'). 모름이면 지표는 null
  보조: first_edit_turn · typo_turns(없는 도구 이름 턴 수) · sleep_turns(sleep 이 든 Bash 턴 수) ·
        reads_before_correct(첫 정답 편집 호출 전 본 대화 읽기 호출 수) · first_correct_edit_turn_raw(성공 여부와 무관한 값) ·
        edits(편집 사건 목록 — 턴·파일별 기준 좌표 바뀐 줄 범위·적중)

해석(ADR 문장을 코드로 옮기며 정한 것 — 테스트 test_fce.py):
- 재생하는 호출 = 본 대화의 Bash·Edit·Write·MultiEdit. 없는 도구 이름(`bash`)은 실행되지 않았으므로 재생하지 않는다.
  Bash 는 is_error 와 무관하게 재생하고(앞 조각의 쓰기는 이미 됐다), 결과가 `<tool_use_error>`(도구가 명령을 실행하지
  않음 — 입력 검증·권한 거부)인 호출만 뺀다. Edit 류는 원자적이라 is_error 면 파일이 그대로다(재생하지 않는다).
- 편집 사건의 '기존 파일' = 기준 사본에 있던 파일(지운 것 포함). 실행 중 새로 만든 파일은 판정 범위가 없고 편집 사건이 아니다.
- 기준 좌표: 파일마다 현재 줄 → 기준 줄 대응을 유지한다. 바꾼 줄(difflib replace 쌍)은 기준 줄 정체를 잇고, 새로 넣은 줄은
  None(바로 앞 기준 줄이 앵커). 바뀐 줄 = 지우거나 바꾼 줄의 기준 줄(새로 넣었던 줄이면 그 앵커) + 삽입 위치의 앵커.
  파일 맨 앞 삽입의 앵커는 0.
- 판정 범위: 패치의 변경 덩어리(연속한 -/+ 줄)마다 - 줄의 원래 줄 번호. - 줄이 없는 덩어리(순수 삽입)는 앵커(앞 원래 줄)와
  그다음 줄. 문맥 줄은 넣지 않는다(§5.2). -U0 패치에서는 덩어리 = hunk 와 같다.
- Bash 재생: 쓰기 조각(events.bash_writes 가 대상을 찾은 조각)만 실행한다 — python(-c·heredoc·<<<) · sed -i · perl -i ·
  cat/echo/printf 리다이렉트 · heredoc tee · cp/mv/install · patch/git apply(heredoc) · rm. 그 밖(대상 모름·if/case 갈래 안·
  스크립트 파일·변수·글롭·하위 에이전트 쓰기·NotebookEdit)은 재생 불가 → 모름.
- 자기 검사: 재생 최종 트리의 diff(runner.write_diff, 실행 때와 같은 함수)가 기록 diff 와 글자 그대로 같아야 한다.
- 읽기 호출 = Read·Grep·Glob·LS 도구, 또는 편집 사건이 아닌 Bash 중 읽기 명령(cat·sed·grep·head 등) 조각이 있는 호출.
"""
import argparse
import difflib
import json
import os
import posixpath
import re
import shutil
import statistics
import subprocess
import sys
import tempfile
from dataclasses import dataclass, field
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import events as E  # noqa: E402
import runner as R  # noqa: E402

ROOT = HERE.parent.parent
INF = float("inf")
REPLAY_TOOLS = ("Bash",) + E.EDIT_TOOLS
READ_TOOLS = {"Read", "Grep", "Glob", "LS"}
READ_CMDS = {"cat", "head", "tail", "sed", "grep", "egrep", "fgrep", "rg", "ag", "find", "ls", "awk", "nl", "wc", "less",
             "more", "tree", "cut", "sort", "uniq", "diff", "git", "file", "stat", "xxd", "od", "strings"}
REDIRECT_OPS = (">", ">>", ">|", "&>", "&>>")
SIMPLE_PRODUCERS = {"cat", "echo", "printf", "true", ":"}
TIMEOUT_S = 120


# ---------------------------------------------------------------- 판정 범위 (§5.2)

def patch_ranges(text: str) -> dict:
    """unified diff → {경로: set(기준 줄)}. 변경 덩어리(연속한 -/+ 줄)마다 - 줄의 원래 줄 번호, - 가 없는 덩어리(순수 삽입)는
    앵커(앞 원래 줄)와 그다음 줄. 문맥 줄은 넣지 않는다(§5.2). 경로 = `--- a/…` 에서 첫 디렉터리를 뗀 것(사본 루트 기준).
    새 파일(--- /dev/null)은 범위가 없다."""
    out, path = {}, None
    old_no = old_left = new_left = 0
    minus, plus, anchor = [], False, 0

    def flush():
        nonlocal minus, plus
        if path is not None and (minus or plus):
            out.setdefault(path, set()).update(minus if minus else {anchor, anchor + 1})
        minus, plus = [], False

    for line in text.splitlines():
        if old_left > 0 or new_left > 0:  # hunk 본문
            tag = line[:1]
            if tag == "\\":
                continue
            if tag == "-":
                if not minus and not plus:
                    anchor = old_no - 1
                minus.append(old_no)
                old_no, old_left = old_no + 1, old_left - 1
            elif tag == "+":
                if not minus and not plus:
                    anchor = old_no - 1
                plus = True
                new_left -= 1
            else:  # 문맥 줄(' ' 또는 빈 줄)
                flush()
                old_no, old_left, new_left = old_no + 1, old_left - 1, new_left - 1
            if old_left <= 0 and new_left <= 0:
                flush()
            continue
        m = re.match(r"@@ -(\d+)(?:,(\d+))? \+\d+(?:,(\d+))? @@", line)
        if m:
            old_no = int(m[1])
            old_left = int(m[2]) if m[2] is not None else 1
            new_left = int(m[3]) if m[3] is not None else 1
            if old_left == 0:  # 순수 삽입 hunk(-U0): @@ -N,0 … @@ 은 원래 줄 N 뒤에 넣는다
                old_no += 1
        elif line.startswith("--- "):
            t = line[4:].split("\t")[0].strip()
            path = None if t == "/dev/null" else (t.split("/", 1)[1] if "/" in t else t)
    return out


def merge_ranges(*rs: dict) -> dict:
    out = {}
    for r in rs:
        for p, ls in r.items():
            out.setdefault(p, set()).update(ls)
    return out


def parse_span(s: str) -> tuple:
    m = re.fullmatch(r"(\S+):(\d+)(?:-(\d+))?", s.strip())
    if not m:
        raise ValueError(f"그룹 범위 형식 오류: {s!r}")
    return m[1], int(m[2]), int(m[3] or m[2])


def group_lines(defs: dict) -> dict:
    """{그룹: [경로:시작-끝]} → {그룹: {경로: set(줄)}}."""
    out = {}
    for g, spans in defs.items():
        d = out.setdefault(g, {})
        for s in spans:
            p, a, b = parse_span(s)
            d.setdefault(p, set()).update(range(a, b + 1))
    return out


# ---------------------------------------------------------------- 재생

class Unreplayable(Exception):
    pass


@dataclass
class FileMap:
    """현재 파일 줄(0-based 위치) → 기준 줄 번호(1-based) 또는 None(실행 중 새로 넣은 줄)."""
    cur: list

    @classmethod
    def fresh(cls, n: int):
        return cls(list(range(1, n + 1)))

    def anchor(self, i: int) -> int:
        """현재 위치 i 앞(포함하지 않음)의 가장 가까운 기준 줄. 없으면 0."""
        for k in range(i - 1, -1, -1):
            if self.cur[k] is not None:
                return self.cur[k]
        return 0

    def apply(self, old: list, new: list) -> set:
        """old → new 편집. 바뀐 기준 줄 집합을 돌려주고 대응을 갱신한다."""
        touched, nxt = set(), []
        for tag, i1, i2, j1, j2 in difflib.SequenceMatcher(None, old, new, autojunk=False).get_opcodes():
            if tag == "equal":
                nxt += self.cur[i1:i2]
                continue
            for i in range(i1, i2):  # 지우거나 바꾼 줄 — 그 기준 줄(실행 중 넣었던 줄이면 그 앵커)
                touched.add(self.cur[i] if self.cur[i] is not None else self.anchor(i))
            if j2 - j1 > i2 - i1:  # 넣은 줄(순수 삽입, 또는 바꾼 줄보다 많이 쓴 나머지) — 바로 앞 기준 줄(앵커)
                touched.add(self.anchor(i2))
            if tag == "replace":
                k = min(i2 - i1, j2 - j1)
                nxt += self.cur[i1:i1 + k] + [None] * (j2 - j1 - k)
            elif tag == "insert":
                nxt += [None] * (j2 - j1)
        self.cur = nxt
        return touched


def snapshot(root: Path) -> dict:
    """사본 기준 상대 경로 → 바이트(일반 파일만, runner.DIFF_SKIP_DIRS 제외 — write_diff 와 같은 범위)."""
    out = {}
    for d, dirs, files in os.walk(root, followlinks=False):
        dirs[:] = [x for x in dirs if x not in R.DIFF_SKIP_DIRS and not os.path.islink(os.path.join(d, x))]
        for f in files:
            p = Path(d, f)
            if p.is_file() and not p.is_symlink():
                out[p.relative_to(root).as_posix()] = p.read_bytes()
    return out


def _lines(b) -> list:
    return [] if b is None else b.decode("utf-8", errors="replace").splitlines(keepends=True)


WORK_RE = re.compile(r"(?<![\w.\-/])/work(?=/|['\"\s)\],;]|$)")


def map_text(s: str, root: Path) -> str:
    return WORK_RE.sub(str(root).replace("\\", "\\\\"), s)


def map_arg(a: str, root: Path) -> str:
    if a == E.WORK or a.startswith(E.WORK + "/"):
        return str(root) + a[len(E.WORK):]
    return a


def host_cwd(cwd: str, root: Path) -> Path:
    if cwd == E.UNKNOWN_CWD or not E.in_work(cwd):
        raise Unreplayable(f"cwd:{cwd}")
    p = Path(map_arg(cwd, root))
    if not p.is_dir():
        raise Unreplayable(f"cwd-missing:{cwd}")
    return p


def _run(args, cwd, stdin=None, root=None):
    env = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "HOME": str(root or cwd), "LANG": "C.UTF-8",
           "GIT_CEILING_DIRECTORIES": str(Path(root or cwd).parent)}
    return subprocess.run(args, cwd=cwd, input=stdin, capture_output=True, text=True, timeout=TIMEOUT_S, env=env)


def _check_outside(args: list, root: Path):
    for a in args:
        if a.startswith("/") and not (a == E.WORK or a.startswith(E.WORK + "/")) and a not in ("/dev/null",):
            raise Unreplayable(f"outside-path:{a}")


def replay_seg(s: E.Seg, root: Path):
    """쓰기 조각 하나를 사본에서 실행한다. 재생할 수 없으면 Unreplayable."""
    cwd = host_cwd(s.cwd, root)
    pos = E._pos(s.args)
    outs = [(op, t) for op, t in s.redir if op in REDIRECT_OPS and not t.startswith("&")]
    name = s.name
    if name.startswith("python"):
        here = [t for op, t in s.redir if op == "<<<"]
        if "-c" in s.args and s.args.index("-c") + 1 < len(s.args):
            i = s.args.index("-c")
            r = _run(["python3", "-I", "-c", map_text(s.args[i + 1], root)] + [map_arg(a, root) for a in s.args[i + 2:]],
                     cwd, root=root)
        elif s.body or here:
            r = _run(["python3", "-I", "-"], cwd, stdin=map_text(s.body or here[0], root) + "\n", root=root)
        else:
            raise Unreplayable("python-script")
        return _write_outs(outs, r.stdout, cwd, root)
    if name == "sed" or name in ("perl", "ruby"):
        if name == "ruby":
            raise Unreplayable("ruby")
        args = [map_arg(a, root) for a in s.args]
        _check_outside(E._files_after_script(s.args, {"-e", "-f", "--expression", "--file"}) if name == "sed"
                       else E._perl_files(s.args), root)
        _check_outside([v for k, v in zip(s.args, s.args[1:]) if k in ("-f", "--file")]
                       + [a.split("=", 1)[1] for a in s.args if a.startswith("--file=")], root)  # 스크립트 파일(사본 밖이면 내용 모름)
        r = _run([name] + args, cwd, stdin=s.body or None, root=root)
        return _write_outs(outs, r.stdout, cwd, root)
    if name in ("cp", "mv", "install", "rm"):
        _check_outside(pos, root)
        _run([name] + [map_arg(a, root) for a in s.args], cwd, root=root)
        return _write_outs(outs, "", cwd, root)
    if name == "patch" or (name == "git" and s.args[:1] == ["apply"]):
        if not s.body:
            raise Unreplayable(f"{name}-no-body")
        _check_outside([a for a in s.args if a.startswith("/")], root)
        _run([name] + [map_arg(a, root) for a in s.args], cwd, stdin=s.body + "\n", root=root)
        return _write_outs(outs, "", cwd, root)
    if name == "tee":
        if not s.body:
            raise Unreplayable("tee-pipe")
        _check_outside(pos, root)
        append = any(a in ("-a", "--append") for a in s.args)
        for f in pos:
            rel = E.resolve(E.WORK + str(cwd)[len(str(root)):], f)
            if rel and not rel.startswith(("/", "~", "$")):
                _write(root, rel, s.body + "\n", append)
        return _write_outs(outs, s.body + "\n", cwd, root)
    if name in SIMPLE_PRODUCERS and outs:
        if name == "cat":
            _check_outside(pos, root)
            r = _run(["cat"] + [map_arg(a, root) for a in s.args], cwd, stdin=(s.body + "\n") if s.body else "",
                     root=root)
            out = r.stdout
        elif name in ("true", ":"):
            out = ""
        else:
            r = _run([name] + s.args, cwd, root=root)
            out = r.stdout
        return _write_outs(outs, out, cwd, root)
    raise Unreplayable(f"writer:{name}")


def _write(root: Path, rel: str, data: str, append: bool):
    """사본 기준 상대 경로 rel 에 쓴다."""
    p = Path(root, rel)
    p.parent.mkdir(parents=True, exist_ok=True)
    with open(p, "a" if append else "w", encoding="utf-8") as fh:
        fh.write(data)


def _write_outs(outs, data, cwd: Path, root: Path):
    """리다이렉트 대상에 출력을 쓴다. 사본 밖(/tmp·/dev/null·~·$…)은 버린다."""
    for op, t in outs:
        rel = E.resolve(E.WORK + str(cwd)[len(str(root)):], t)
        if not rel or rel == "-" or rel.startswith(("/", "~", "$", "&")):
            continue
        _write(root, rel, data, op in (">>", "&>>"))


def _rm_targets(s: E.Seg) -> list:
    return [a for a in E._pos(s.args)] if s.name in ("rm", "unlink") else []


def bash_write_segs(segs: list) -> list:
    """재생할 조각: events.bash_writes 가 쓰기로 본 조각 + rm(사본 안 대상)."""
    out = []
    for s in segs:
        tg, unk = E.bash_writes([s])
        rm = [E.resolve(s.cwd, a) for a in _rm_targets(s)] if s.cwd != E.UNKNOWN_CWD else []
        if tg or unk or any(E._work_target(x) for x in rm):
            if unk:
                raise Unreplayable("bash_writes:" + ",".join(unk))
            if s.cond:
                raise Unreplayable("cond-branch")
            out.append(s)
    return out


def replay_edit_tool(c: E.Call, root: Path):
    if c.name == "NotebookEdit":
        raise Unreplayable("NotebookEdit")
    p = str(c.input.get("file_path", ""))
    if not (p == E.WORK or p.startswith(E.WORK + "/")):
        if p.startswith("/"):
            return  # 사본 밖
        p = posixpath.normpath(posixpath.join(E.WORK, p))
    hp = Path(map_arg(p, root))
    if c.name == "Write":
        hp.parent.mkdir(parents=True, exist_ok=True)
        hp.write_text(str(c.input.get("content", "")), encoding="utf-8")
        return
    if not hp.is_file():
        return
    s = hp.read_text(encoding="utf-8")
    edits = c.input.get("edits") if c.name == "MultiEdit" else [c.input]
    for e in edits or []:
        old, new = str(e.get("old_string", "")), str(e.get("new_string", ""))
        if not old or old not in s:
            return  # 도구가 실패했을 것 — MultiEdit 는 원자적
        s = s.replace(old, new) if e.get("replace_all") else s.replace(old, new, 1)
    hp.write_text(s, encoding="utf-8")


def not_executed(c: E.Call) -> bool:
    return c.is_error and c.result.lstrip().startswith("<tool_use_error>")


# ---------------------------------------------------------------- 분석

@dataclass
class Result:
    turns: int = 0
    edits: list = field(default_factory=list)
    first_edit_turn: int = None
    first_correct_turn: int = None
    first_correct_index: int = None
    group_turns: dict = field(default_factory=dict)
    unknown_reasons: list = field(default_factory=list)
    typo_turns: int = 0
    sleep_turns: int = 0
    reads_before_correct: int = None


def analyze(cs: list, turns: int, work: Path, ranges: dict, groups: dict = None) -> Result:
    """cs 를 work(기준 사본의 작업용 복사본 — 제자리에서 바뀐다)에서 재생한다. ranges = fix ∪ alt {경로: set(줄)}.
    groups = {그룹: {경로: set(줄)}} 또는 None."""
    res = Result(turns=turns)
    base = snapshot(work)
    maps = {p: FileMap.fresh(len(_lines(b))) for p, b in base.items()}
    cur = dict(base)
    cwd = E.WORK
    typo, sleep, reads = set(), set(), 0
    for idx, c in enumerate(cs):
        if c.sub:
            if c.name in E.EDIT_TOOLS or (c.name == "Bash" and _has_write(c)):
                res.unknown_reasons.append(f"t{c.turn}:subagent-write")
            continue
        if "No such tool available" in c.result and c.is_error:
            typo.add(c.turn)
        segs, after = E.segments(str(c.input.get("command", "")), cwd) if c.name == "Bash" else ([], cwd)
        if c.name == "Bash" and any(s.name == "sleep" for s in segs):
            sleep.add(c.turn)
        changed = False
        if c.name in REPLAY_TOOLS and not not_executed(c) and not (c.name in E.EDIT_TOOLS and c.is_error):
            did = c.name != "Bash"
            try:
                if c.name == "Bash":
                    for s in bash_write_segs(segs):
                        did = True
                        replay_seg(s, work)
                else:
                    replay_edit_tool(c, work)
            except (Unreplayable, subprocess.TimeoutExpired, OSError) as e:
                did = True
                res.unknown_reasons.append(f"t{c.turn}:{type(e).__name__}:{e}")
            now = snapshot(work) if did else cur
            touched_files = {}
            for p in base:
                if cur.get(p) == now.get(p):
                    continue
                touched_files[p] = maps[p].apply(_lines(cur.get(p)), _lines(now.get(p)))
            cur = now
            if touched_files:
                changed = True
                hits = {p: sorted(ls & ranges.get(p, set())) for p, ls in touched_files.items()}
                hit = any(hits.values())
                ev = {"turn": c.turn, "call": idx, "tool": c.name, "is_error": c.is_error,
                      "lines": {p: _compact(ls) for p, ls in touched_files.items()},
                      "hit": hit}
                res.edits.append(ev)
                if res.first_edit_turn is None:
                    res.first_edit_turn = c.turn
                if hit and res.first_correct_turn is None:
                    res.first_correct_turn, res.first_correct_index = c.turn, idx
                    res.reads_before_correct = reads
                for g, gl in (groups or {}).items():
                    if g not in res.group_turns and any(set(hits[p]) & gl.get(p, set()) for p in hits):
                        res.group_turns[g] = c.turn
        cwd = after
        if not changed and res.first_correct_turn is None and _is_read(c, segs):
            reads += 1
    res.typo_turns, res.sleep_turns = len(typo), len(sleep)
    return res


def _has_write(c: E.Call) -> bool:
    tg, unk = E.bash_writes(E.segments(str(c.input.get("command", "")))[0])
    return bool(tg or unk)


def _is_read(c: E.Call, segs: list) -> bool:
    if c.name in READ_TOOLS:
        return True
    return c.name == "Bash" and not not_executed(c) and any(s.name in READ_CMDS for s in segs)


def _compact(ls) -> list:
    out = []
    for x in sorted(ls):
        if out and x == out[-1][1] + 1:
            out[-1][1] = x
        else:
            out.append([x, x])
    return [f"{a}-{b}" if a != b else str(a) for a, b in out]


# ---------------------------------------------------------------- 기준 사본·oracle (git)

def git(*args, text=False) -> bytes:
    return subprocess.run(["git", "-C", str(ROOT), *args], check=True, capture_output=True, text=text).stdout


def git_show(rev: str, path: str):
    p = subprocess.run(["git", "-C", str(ROOT), "show", f"{rev}:{path}"], capture_output=True)
    return p.stdout.decode() if p.returncode == 0 else None


def oracle_ranges(rev: str, case: str, struct: str) -> tuple:
    """(fix ∪ alt 범위, 쓴 패치 이름 목록) — 그 리비전의 oracle/<case>/fix.<s>.patch · alt-N.<s>.patch."""
    names = [n for n in git("ls-tree", "--name-only", f"{rev}:experiment/oracle/{case}/", text=True).split()
             if re.fullmatch(rf"(fix|alt-\d+)\.{struct}\.patch", n)]
    if not any(n.startswith("fix.") for n in names):
        raise FileNotFoundError(f"{rev}: experiment/oracle/{case}/fix.{struct}.patch 없음")
    rs = [patch_ranges(git_show(rev, f"experiment/oracle/{case}/{n}")) for n in sorted(names)]
    return merge_ranges(*rs), sorted(names)


def build_base(rev: str, case: str, struct: str, dst: Path, cache: Path):
    """그 리비전의 runner.build_work 로 기준 사본을 만든다(그 리비전 트리의 구조 src·템플릿·과제 공개 파일·inject 패치)."""
    tree = cache / f"tree-{rev}-{case}-{struct}"
    if not tree.is_dir():
        want = [f"{struct}/src", "experiment/runner", f"experiment/cases/{case}", f"experiment/oracle/{case}",
                "gradlew", "gradlew.bat", "gradle/wrapper"]
        have = [p for p in want if git("ls-tree", rev, "--", p, text=True).strip()]
        tree.mkdir(parents=True)
        tar = git("archive", rev, "--", *have)
        subprocess.run(["tar", "x", "-C", str(tree)], input=tar, check=True)
    code = ("import sys; from pathlib import Path; sys.path.insert(0, sys.argv[1]); import runner; "
            "runner.build_work(sys.argv[2], sys.argv[3], Path(sys.argv[4]))")
    p = subprocess.run([sys.executable, "-I", "-c", code, str(tree / "experiment" / "runner"), case, struct, str(dst)],
                       capture_output=True, text=True)
    if p.returncode != 0:
        raise RuntimeError(f"build_work({rev},{case},{struct}) 실패: {p.stderr[-800:]}")


def load_groups(spec: str) -> dict:
    if Path(spec).is_file():
        return json.loads(Path(spec).read_text())
    rev, _, path = spec.partition(":")
    txt = git_show(rev, path)
    if txt is None:
        raise FileNotFoundError(spec)
    return json.loads(txt)


def fmt(v):
    return "+inf" if v == INF else v


def _is_test_path(p: str) -> bool:
    """§5.3 개정 2 — all_fix_fallback 이 세지 않는 테스트 쪽 파일(공개 테스트·test.sh)."""
    return "/src/test/" in p or p.startswith("checks/") or p.rsplit("/", 1)[-1] == "test.sh"


def evaluate(cs: list, turns: int, base: Path, ranges: dict, groups, recorded: bytes, success: bool,
             scratch: Path) -> dict:
    """기준 사본 base 의 복사본(scratch/replay)에서 재생하고 자기 검사(§5.4)와 §5.1·§5.3 값을 낸다. groups = group_lines 결과
    또는 None. recorded = 기록 diff 바이트."""
    work = scratch / "replay"
    if work.exists():
        shutil.rmtree(work)
    shutil.copytree(base, work, symlinks=True)
    try:
        res = analyze(cs, turns, work, ranges, groups)
        reasons = list(res.unknown_reasons)
        replay_diff = scratch / "replay.diff"
        R.write_diff(base, work, replay_diff)  # 실행 때 기록 diff 를 만든 함수 그대로
        match = replay_diff.read_bytes() == recorded
    finally:
        shutil.rmtree(work, ignore_errors=True)
    if not match:
        reasons.append("final-tree-mismatch")
    unknown = bool(reasons)
    fce, fallback = None, False
    if not unknown:
        if not success:
            fce = INF
        elif res.first_correct_turn is not None:
            fce = res.first_correct_turn
        else:  # 성공했지만 정답 편집 없음 — 등록하지 못한 대안 정답(§5.1)
            fce, fallback = res.first_edit_turn, True
    all_turn, missing, all_fallback = None, None, False
    if groups is not None:
        missing = sorted(set(groups) - set(res.group_turns))
        if not unknown:
            if not success:
                all_turn = INF
            elif not missing:
                all_turn = max(res.group_turns.values())
            else:  # 성공했지만 닿지 않은 그룹 — 등록하지 못한 대안 정답(ADR-0008 §5.3 개정 1·2): 테스트 밖 마지막 편집 사건 턴
                all_turn = max((e["turn"] for e in res.edits if any(not _is_test_path(p) for p in e["lines"])),
                               default=None)
                all_fallback = True
    return {
        "turns": turns, "unknown": unknown, "unknown_reasons": reasons, "replay_matches_diff": match,
        "first_correct_edit_turn": fmt(fce), "fce_fallback": fallback,
        "first_correct_edit_turn_raw": res.first_correct_turn,
        "all_fix_touched_turn": fmt(all_turn) if groups is not None else None,
        "group_turns": res.group_turns if groups is not None else None,
        "groups_missing": missing, "all_fix_fallback": all_fallback,
        "primary": fmt(all_turn) if groups is not None else fmt(fce),
        "first_edit_turn": res.first_edit_turn, "typo_turns": res.typo_turns, "sleep_turns": res.sleep_turns,
        "reads_before_correct": res.reads_before_correct, "edits": res.edits,
    }


def run_one(row: dict, results: Path, rev: str, groups_def, cache: Path) -> dict:
    case, struct, run_id = row["case"], row["structure"], row["run_id"]
    out = {"run_id": run_id, "case": case, "structure": struct, "rev": rev, "valid": row.get("valid"),
           "success": row.get("success")}
    ranges, used = oracle_ranges(rev, case, struct)
    out["oracle_patches"] = used
    out["range_lines"] = sum(len(v) for v in ranges.values())
    if groups_def is not None and struct not in groups_def:
        raise KeyError(f"{case}: 그룹 정의에 {struct} 없음")
    groups = group_lines(groups_def[struct]) if groups_def is not None else None
    base = cache / f"base-{rev}-{case}-{struct}"
    if not base.is_dir():
        build_base(rev, case, struct, base, cache)
    evs, _ = E.load((results / row["events"]).read_text(errors="replace").splitlines())
    cs, turns = E.calls(evs)
    recorded = (results / row["diff"]).read_bytes() if row.get("diff") else b""
    scratch = cache / f"run-{run_id}"
    scratch.mkdir(exist_ok=True)
    out.update(evaluate(cs, turns, base, ranges, groups, recorded, bool(row.get("success")), scratch))
    return out


def parse_map(items: list) -> dict:
    out = {}
    for it in items or []:
        k, sep, v = it.partition("=")
        if not sep or not k or not v:
            raise SystemExit(f"CASE=값 형식이 아님: {it!r}")
        out[k] = v
    return out


# ---------------------------------------------------------------- 요약

def _num(v):
    return INF if v == "+inf" else v


def median_str(vals: list):
    if not vals:
        return None
    m = statistics.median(vals)
    return "+inf" if m == INF else (int(m) if float(m).is_integer() else m)


def summarize(rows: list) -> str:
    lines = ["| run | struct | success | unknown | primary | fce | fallback | first_edit | turns | typo | sleep | reads_before |",
             "|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for r in rows:
        lines.append(f"| {r['run_id']} | {r['structure']} | {r['success']} | {r['unknown']} | {r['primary']} | "
                     f"{r['first_correct_edit_turn']} | {r['fce_fallback']} | {r['first_edit_turn']} | {r['turns']} | "
                     f"{r['typo_turns']} | {r['sleep_turns']} | {r['reads_before_correct']} |")
    ok = [r for r in rows if not r["unknown"] and r["primary"] is not None]
    lines.append("")
    lines.append("| 묶음 | n | 성공 중앙값 | 보수 중앙값(실패 +inf) | 값 | fallback | 모름 | 값 없음 |")
    lines.append("|---|---|---|---|---|---|---|---|")
    for label, sel in [("전체", rows)] + [(s, [r for r in rows if r["structure"] == s]) for s in R.STRUCTURES]:
        good = [r for r in sel if r in ok]
        succ = [_num(r["primary"]) for r in good if r["success"]]
        cons = [_num(r["primary"]) for r in good]
        lines.append(f"| {label} | {len(sel)} | {median_str(succ)} | {median_str(cons)} | "
                     f"{[r['primary'] for r in good]} | {sum(r['fce_fallback'] or bool(r.get('all_fix_fallback')) for r in sel)} | "
                     f"{sum(r['unknown'] for r in sel)} | "
                     f"{sum(not r['unknown'] and r['primary'] is None for r in sel)} |")  # 성공·편집 사건 없음(§5.3 개정 2)
    return "\n".join(lines)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    a = sub.add_parser("run", help="results 디렉터리의 실행마다 지표 1행(JSON lines)")
    a.add_argument("results", type=Path)
    a.add_argument("--rev", action="append", required=True, help="CASE=REV (oracle·기준 사본 리비전, *=나머지 전부)")
    a.add_argument("--groups", action="append", help="CASE=PATH 또는 CASE=REV:repo/경로 (S3 그룹 정의 JSON)")
    a.add_argument("--runs", nargs="*", help="이 run_id 만")
    a.add_argument("--table", action="store_true", help="JSON lines 대신 표 + 중앙값")
    a.add_argument("--cache", type=Path, help="기준 사본·재생 작업 디렉터리(기본: 임시, 끝나면 지움)")
    b = sub.add_parser("summary", help="run 출력(JSON lines) 여러 개를 합쳐 표 + 전체·구조별 중앙값")
    b.add_argument("files", nargs="+", type=Path)
    o = ap.parse_args(argv)
    if o.cmd == "summary":
        rows = [json.loads(l) for f in o.files for l in f.read_text().splitlines() if l.strip()]
        print(summarize(rows))
        return 0
    revs = parse_map(o.rev)
    gspec = parse_map(o.groups)
    cache = o.cache or Path(tempfile.mkdtemp(prefix="fce-"))
    cache.mkdir(parents=True, exist_ok=True)
    rows = []
    try:
        seen = {}
        for line in (o.results / "runs.jsonl").read_text().splitlines():
            if line.strip():
                r = json.loads(line)
                seen[r["run_id"]] = r  # 같은 run_id 가 여러 번이면 마지막 행
        for run_id, r in seen.items():
            if o.runs and run_id not in o.runs:
                continue
            if not r.get("events"):
                continue
            rev = revs.get(r["case"]) or revs.get("*")
            if not rev:
                raise SystemExit(f"{r['case']}: --rev 없음")
            gdef = load_groups(gspec[r["case"]]) if r["case"] in gspec else None
            out = run_one(r, o.results, rev, gdef, cache)
            rows.append(out)
            if not o.table:
                print(json.dumps(out, ensure_ascii=False), flush=True)
    finally:
        if not o.cache:
            shutil.rmtree(cache, ignore_errors=True)
    if o.table:
        print(summarize(rows))
    return 0


if __name__ == "__main__":
    sys.exit(main())
