#!/usr/bin/env bash
# 더미 과제 확인: TARGET-NEW 1곳, TARGET-OLD 0곳, done.txt == ok
set -euo pipefail
[ "$(grep -c 'TARGET-NEW' notes/data.txt)" = 1 ] && ! grep -q 'TARGET-OLD' notes/data.txt && [ "$(cat notes/done.txt)" = ok ] && echo PASS || { echo FAIL; exit 1; }
