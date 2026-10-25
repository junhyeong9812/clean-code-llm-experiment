# 값 바꾸기 (러너 스모크용 더미 과제 — 도구 결과 형식 캡처)

`notes/data.txt` 에 `TARGET-OLD` 값이 한 곳 있다. 그 값을 `TARGET-NEW` 로 바꾸고, `notes/done.txt` 파일을 새로 만들어 `ok` 한 줄을 써라.

반드시 이 순서와 도구를 써라(형식 확인용):
1. Grep 도구로 `TARGET-OLD` 를 찾는다(줄 번호 표시, 앞뒤 1줄 맥락 포함).
2. Read 도구로 그 줄 앞뒤 10줄만 읽는다(offset·limit 지정).
3. Edit 도구로 값을 바꾼다.
4. Write 도구로 `notes/done.txt` 를 만든다.
5. `bash test.sh` 를 실행한다.
