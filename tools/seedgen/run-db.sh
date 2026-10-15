#!/usr/bin/env bash
# 실행 1회용 DB 컨테이너: 시드 이미지에서 띄우고 접속 정보를 출력한다. 끝나면 docker rm -f <name>.
# 사용: tools/seedgen/run-db.sh <tag> <name> <port>      예: run-db.sh 500k-s42 ccle-run-001 15433
set -euo pipefail
tag=$1 name=$2 port=$3
start=$(date +%s.%N)
docker run -d --name "$name" -p "127.0.0.1:$port:5432" "ccle-postgres-seed:$tag" > /dev/null
for _ in $(seq 300); do
  docker exec "$name" pg_isready -U ccle -d ccle_seed -q 2>/dev/null && break
  sleep 0.2
done
docker exec "$name" psql -U ccle -d ccle_seed -Atqc "SELECT 1" > /dev/null
printf 'ready %.1fs\n' "$(echo "$(date +%s.%N) - $start" | bc)"
echo "DB_URL=jdbc:postgresql://127.0.0.1:$port/ccle_seed DB_USER=ccle DB_PASSWORD=ccle"
