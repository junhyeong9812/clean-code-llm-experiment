#!/usr/bin/env bash
# 더미 과제 확인: notes/greeting.txt == "hello, <users 행 수>"
set -euo pipefail
n=$(PGPASSWORD="$DB_PASSWORD" psql -h db -U "$DB_USER" -d ccle_seed -Atqc "SELECT count(*) FROM users")
want="hello, $n"
got=$(cat notes/greeting.txt)
if [ "$got" = "$want" ]; then echo PASS; else echo "FAIL: '$got' != '$want'"; exit 1; fi
