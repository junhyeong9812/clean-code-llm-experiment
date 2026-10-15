#!/usr/bin/env bash
# CSV → PostgreSQL: 빈 DB를 새로 만들고 테이블 → COPY → 제약(FK·UNIQUE·인덱스, 걸리며 전체 검증) → 시퀀스 → ANALYZE.
# 사용: tools/seedgen/load.sh <csv_dir> <container> <db>
# 대상 DB가 있으면 지우고 다시 만든다(시드 전용 DB 이름만 쓸 것).
set -euo pipefail
csv=$1 container=$2 db=$3
root=$(cd "$(dirname "$0")/../.." && pwd)
psql() { docker exec -i "$container" psql -U ccle -v ON_ERROR_STOP=1 -q "$@"; }

case "$db" in ccle_god|ccle_layered|ccle|postgres) echo "개발 DB($db)에는 적재하지 않는다" >&2; exit 2;; esac
[ -f "$csv/manifest.json" ] || { echo "manifest.json 없음: $csv" >&2; exit 2; }
# CSV가 manifest 와 바이트 단위로 같은지(손상·부분 생성·다른 시드 섞임 차단)
python3 - "$csv" <<'PY'
import hashlib, json, sys
from pathlib import Path
d = Path(sys.argv[1]); m = json.loads((d / "manifest.json").read_text()); bad = []
for t, want in m["sha256"].items():
    h = hashlib.sha256()
    with open(d / f"{t}.csv", "rb") as f:
        for c in iter(lambda: f.read(1 << 20), b""):
            h.update(c)
    if h.hexdigest() != want:
        bad.append(t)
if bad:
    sys.exit("sha256 불일치: " + ", ".join(bad))
PY
root_dir=$(docker info -f '{{.DockerRootDir}}')
[ -n "$root_dir" ] || { echo "도커 data-root 를 알 수 없음" >&2; exit 2; }
need_gb=$(( $(du -sk "$csv" | cut -f1) * 4 / 1024 / 1024 + 1 ))   # DB ≈ CSV×3.1(1천 명 실측)
free_gb=$(( $(df -Pk "$root_dir" | awk 'NR==2{print $4}') / 1024 / 1024 ))
[ "$free_gb" -gt "$need_gb" ] || { echo "디스크 부족: 여유 ${free_gb}GB, 필요 약 ${need_gb}GB" >&2; exit 2; }

psql -d postgres -c "DROP DATABASE IF EXISTS $db" -c "CREATE DATABASE $db OWNER ccle"
psql -d "$db" < "$root/docker/schema/tables.sql"

# FK 순서와 무관(제약은 나중에 건다). manifest 의 행 수와 대조한다.
tables=$(python3 -c "import json,sys; print(' '.join(json.load(open(sys.argv[1]))['rows']))" "$csv/manifest.json")
for t in $tables; do
  cols=$(head -1 "$csv/$t.csv")
  psql -d "$db" -c "\\copy $t ($cols) FROM STDIN WITH (FORMAT csv, HEADER true)" < "$csv/$t.csv"
  want=$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['rows'][sys.argv[2]])" "$csv/manifest.json" "$t")
  got=$(psql -d "$db" -Atc "SELECT count(*) FROM $t")
  [ "$want" = "$got" ] || { echo "행 수 불일치 $t: manifest $want, DB $got" >&2; exit 1; }
  printf '%-24s %12s\n' "$t" "$got"
done

psql -d "$db" < "$root/docker/schema/constraints.sql"

# bigserial 시퀀스를 최대 id 다음으로
for t in $tables; do
  case $(head -1 "$csv/$t.csv") in id,*)
    psql -d "$db" -Atc "SELECT setval(pg_get_serial_sequence('$t', 'id'), coalesce((SELECT max(id) FROM $t), 0) + 1, false)" > /dev/null;;
  esac
done
# 실행 컨테이너에서 autovacuum 이 대형 테이블을 다시 쓰지 않게(오버레이 copy-up 방지) 적재 직후 얼린다
psql -d "$db" -c "VACUUM (FREEZE, ANALYZE)"
echo "적재 완료: $db"
