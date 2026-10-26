# 에이전트 외부 접속 허용 목록 프록시(tinyproxy). 에이전트 컨테이너는 --internal 네트워크에 있어 이 프록시로만 나간다.
FROM alpine:3.20
RUN apk add --no-cache tinyproxy
COPY tinyproxy.conf /etc/tinyproxy/tinyproxy.conf
COPY allow.txt /etc/tinyproxy/allow.txt
EXPOSE 3128
CMD ["tinyproxy", "-d", "-c", "/etc/tinyproxy/tinyproxy.conf"]
