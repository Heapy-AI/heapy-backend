#!/usr/bin/env bash
# 작성자: 김진우 — 인증서 갱신 성공 후 Nginx가 새 인증서를 읽도록 한다.
set -Eeuo pipefail
source /opt/heapy/https/images.sh
exec 9>/run/lock/heapy-certbot.lock
flock -n 9 || exit 0
args=()
if [[ ${1:-} == --dry-run ]]; then args+=(--dry-run); elif [[ $# != 0 ]]; then exit 1; fi
docker run --rm --name heapy-certbot-renew --network host \
    -v /etc/letsencrypt:/etc/letsencrypt \
    -v /var/lib/letsencrypt:/var/lib/letsencrypt \
    -v /var/log/letsencrypt:/var/log/letsencrypt \
    -v /opt/heapy/https/acme:/var/www/acme \
    "$CERTBOT_IMAGE" renew --cert-name heapy-ip --non-interactive "${args[@]}"
openssl x509 -in /etc/letsencrypt/live/heapy-ip/cert.pem -checkend 86400 -noout
docker exec heapy-https nginx -c /etc/heapy/nginx.conf -t
docker exec heapy-https nginx -c /etc/heapy/nginx.conf -s reload
