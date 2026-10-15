#!/usr/bin/env bash
# 시드 이미지: CSV를 적재·검증한 PGDATA를 이미지 레이어에 담는다(볼륨 밖 경로 /pgseed/data).
# 실행마다 이 이미지로 컨테이너를 띄우면 CSV를 다시 적재하지 않는다(run-db.sh).
# 사용: tools/seedgen/build-image.sh <csv_dir> <tag>     예: build-image.sh ~/ccle-csv/500k 500k-s42
set -euo pipefail
csv=$1 tag=$2
here=$(cd "$(dirname "$0")" && pwd)
name=ccle-seed-build
image=ccle-postgres-seed:$tag

root_dir=$(docker info -f '{{.DockerRootDir}}')
[ -n "$root_dir" ] || { echo "도커 data-root 를 알 수 없음" >&2; exit 2; }
need_gb=$(( $(du -sk "$csv" | cut -f1) * 7 / 1024 / 1024 + 2 ))   # DB ≈ CSV×3.1(1천 명 실측), 커밋 순간 컨테이너+이미지 두 벌
free_gb=$(( $(df -Pk "$root_dir" | awk 'NR==2{print $4}') / 1024 / 1024 ))
[ "$free_gb" -gt "$need_gb" ] || { echo "디스크 부족: 여유 ${free_gb}GB, 필요 약 ${need_gb}GB" >&2; exit 2; }

docker rm -f "$name" >/dev/null 2>&1 || true
# 적재 중에만 내구성 설정을 끈다. 커밋할 때 CMD 를 기본값으로 되돌린다.
docker run -d --name "$name" --shm-size=1g -e POSTGRES_USER=ccle -e POSTGRES_PASSWORD=ccle -e POSTGRES_DB=postgres \
  -e PGDATA=/pgseed/data postgres:17 \
  -c fsync=off -c full_page_writes=off -c synchronous_commit=off \
  -c wal_level=minimal -c max_wal_senders=0 -c max_wal_size=2GB \
  -c maintenance_work_mem=1GB > /dev/null
# init 단계의 임시 서버가 아니라 본 서버가 뜰 때까지 기다린다
for _ in $(seq 120); do
  docker logs "$name" 2>&1 | grep -q "PostgreSQL init process complete" \
    && docker exec "$name" pg_isready -U ccle -d postgres -q && break
  sleep 1
done

"$here/load.sh" "$csv" "$name" ccle_seed
echo "불변식 검증…"
docker exec -i "$name" psql -U ccle -d ccle_seed -v ON_ERROR_STOP=1 -q < "$here/verify.sql"

docker exec "$name" psql -U ccle -d postgres -qc "CHECKPOINT"
old_id=$(docker image inspect -f '{{.Id}}' "$image" 2>/dev/null || true)   # 같은 태그의 지금 이미지(라벨 도입 전 빌드도 지우기 위해)
docker stop "$name" > /dev/null
docker commit --change 'CMD ["postgres"]' \
  --change "LABEL ccle.seed.manifest-sha256=$(sha256sum "$csv/manifest.json" | cut -d' ' -f1)" \
  --change "LABEL ccle.seed.tag=$tag" \
  "$name" "$image" > /dev/null
docker rm "$name" > /dev/null
# 시드 이미지는 태그(규모)마다 하나만 둔다: 같은 태그로 다시 빌드하면 이전 빌드가 dangling 이 되므로 그것을 지운다.
# 다른 태그(예: 테스트용 1k, 과제용 500k)는 남긴다. 쓰는 컨테이너가 있으면 지우지 않고 알린다.
new_id=$(docker image inspect -f '{{.Id}}' "$image")
for id in $(printf '%s\n' $old_id $(docker image ls -q --no-trunc -f dangling=true -f "label=ccle.seed.tag=$tag") | sort -u); do
  [ -z "$id" ] || [ "$id" = "$new_id" ] && continue
  if [ -n "$(docker ps -aq -f ancestor="$id")" ]; then echo "남김(사용 중인 컨테이너 있음): $id" >&2; continue; fi
  docker image rm -f "$id" > /dev/null && echo "지움: 이전 시드 이미지 $id"
done
docker image ls "$image" --format '{{.Repository}}:{{.Tag}} {{.Size}}'
