#!/usr/bin/env bash
# 작성자: 김진우 — 약관 동의는 사용자가 터미널에서 직접 수행한다.
set -Eeuo pipefail
[[ $EUID == 0 ]] || { echo 'sudo로 실행하세요.' >&2; exit 1; }
source /opt/heapy/https/images.sh
exec 9>/run/lock/heapy-certbot.lock
flock -n 9 || { echo '인증서 작업이 이미 진행 중입니다.' >&2; exit 1; }
[[ -t 0 && -t 1 ]] || { echo 'Session Manager의 대화형 터미널에서 실행하세요.' >&2; exit 1; }
docker run --rm -it --name heapy-certbot --network host \
    -v /etc/letsencrypt:/etc/letsencrypt \
    -v /var/lib/letsencrypt:/var/lib/letsencrypt \
    -v /var/log/letsencrypt:/var/log/letsencrypt \
    -v /opt/heapy/https/acme:/var/www/acme \
    "$CERTBOT_IMAGE" certonly --webroot --webroot-path /var/www/acme \
    --preferred-profile shortlived --ip-address 13.125.12.94 --cert-name heapy-ip
