#!/usr/bin/env bash
# 작성자: 김진우 — 기존 서비스에 손대지 않고 HTTPS 준비와 활성화를 분리한다.
set -Eeuo pipefail
SOURCE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
TARGET=/opt/heapy/https
source "$SOURCE/images.sh"
[[ $EUID == 0 ]] || exit 1
MODE=${1:?inspect, bootstrap 또는 activate 필요}
[[ $MODE == inspect || $MODE == bootstrap || $MODE == activate || $MODE == repair ]] || exit 1

verify_instance() {
    local token instance public_ip
    token=$(curl -fsS --max-time 3 -X PUT -H 'X-aws-ec2-metadata-token-ttl-seconds: 60' http://169.254.169.254/latest/api/token)
    instance=$(curl -fsS --max-time 3 -H "X-aws-ec2-metadata-token: $token" http://169.254.169.254/latest/meta-data/instance-id)
    public_ip=$(curl -fsS --max-time 3 -H "X-aws-ec2-metadata-token: $token" http://169.254.169.254/latest/meta-data/public-ipv4)
    [[ $instance == i-055b8632e5b93fd96 && $public_ip == 13.125.12.94 ]] || { echo '인스턴스 또는 Elastic IP 불일치.' >&2; return 1; }
    echo '인스턴스 및 Elastic IP 일치.'
}

proxy() {
    docker run "$@" --network host --read-only --tmpfs /tmp:rw,nosuid,size=128m \
        --cap-drop ALL --cap-add NET_BIND_SERVICE --cap-add SETUID --cap-add SETGID \
        --cap-add CHOWN --security-opt no-new-privileges:true \
        --log-opt max-size=10m --log-opt max-file=3 \
        -v "$TARGET:/etc/heapy:ro" \
        -v "$TARGET/acme:/var/www/acme:ro" \
        -v /etc/letsencrypt:/etc/letsencrypt:ro \
        --entrypoint nginx "$NGINX_IMAGE" -c /etc/heapy/nginx.conf -g 'daemon off;'
}

verify_instance
exec 8>/run/lock/heapy-https-setup.lock
flock -n 8 || { echo 'HTTPS 설정 작업이 실행 중입니다.' >&2; exit 1; }
if [[ $MODE == inspect ]]; then
    command -v docker curl openssl ss
    docker ps --format '{{.Names}} {{.Ports}}'
    docker ps -a --filter name=heapy --format '{{.Names}} {{.Status}}'
    if docker container inspect heapy-https >/dev/null 2>&1; then
        docker logs --tail 8 heapy-https 2>&1 | sed -n '/\[emerg\]/p'
    fi
    printf '백엔드 헬스 HTTP 상태: '
    curl --silent --max-time 5 -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/actuator/health || true
    ss -ltn '( sport = :80 or sport = :443 or sport = :8080 )'
    if [[ -d $TARGET ]]; then echo 'HTTPS 작업 디렉터리 존재'; else echo 'HTTPS 작업 디렉터리 없음'; fi
    if [[ -e /etc/letsencrypt/live/heapy-ip/cert.pem ]]; then echo 'IP 인증서 존재'; else echo 'IP 인증서 없음'; fi
    exit 0
fi

if [[ $MODE == repair ]]; then
    # 인증서 발급 전, 이 스크립트가 만든 HTTP 설정에만 임시 경로 수정 적용.
    [[ $(docker inspect --format '{{index .Config.Labels "com.heapy.component"}}' heapy-https) == https ]] || exit 1
    [[ ! -e /etc/letsencrypt/live/heapy-ip/cert.pem ]] || exit 1
    diff -q "$TARGET/nginx.conf" <(sed '/fastcgi_temp_path/d; /uwsgi_temp_path/d; /scgi_temp_path/d' "$SOURCE/nginx-http.conf")
    install -m 644 "$TARGET/nginx.conf" "$TARGET/nginx.before-repair.conf"
    install -m 644 "$SOURCE/nginx-http.conf" "$TARGET/nginx.conf"
    install -m 644 "$SOURCE/nginx-https.conf" "$TARGET/nginx-https.conf"
    docker restart heapy-https >/dev/null
    curl -fsS --retry 5 --retry-connrefused --retry-delay 1 http://127.0.0.1/.well-known/acme-challenge/heapy-probe
    docker exec heapy-https nginx -c /etc/heapy/nginx.conf -t
    [[ $(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1/api/users/me) == 404 ]]
    systemctl daemon-reload
    echo '인증서 발급 전 HTTP 설정 복구 완료.'
    exit 0
fi

if [[ $MODE == bootstrap ]]; then
    [[ ! -e $TARGET ]] || { echo '기존 HTTPS 설정이 있어 자동 덮어쓰기를 중단합니다.' >&2; exit 1; }
    [[ -z $(ss -H -ltn '( sport = :80 or sport = :443 )') ]] || { echo '80 또는 443 포트가 사용 중입니다.' >&2; exit 1; }
    if docker container inspect heapy-https >/dev/null 2>&1; then echo '동일 이름 컨테이너가 존재합니다.' >&2; exit 1; fi
    for unit in /etc/systemd/system/heapy-certbot-renew.service /etc/systemd/system/heapy-certbot-renew.timer; do
        [[ ! -e $unit ]] || { echo '동일 이름 갱신 설정이 존재합니다.' >&2; exit 1; }
    done
    docker pull "$NGINX_IMAGE" >/dev/null
    docker pull "$CERTBOT_IMAGE" >/dev/null
    install -d -m 700 "$TARGET"
    install -d -m 755 "$TARGET/acme/.well-known/acme-challenge"
    install -m 644 "$SOURCE/probe.txt" "$TARGET/acme/.well-known/acme-challenge/heapy-probe"
    for file in images.sh issue-certificate.sh renew-certificate.sh; do install -m 700 "$SOURCE/$file" "$TARGET/$file"; done
    install -m 644 "$SOURCE/nginx-http.conf" "$TARGET/nginx.conf"
    install -m 644 "$SOURCE/nginx-https.conf" "$TARGET/nginx-https.conf"
    install -d -m 700 /etc/letsencrypt /var/lib/letsencrypt /var/log/letsencrypt
    install -m 644 "$SOURCE/heapy-certbot-renew.service" /etc/systemd/system/heapy-certbot-renew.service
    install -m 644 "$SOURCE/heapy-certbot-renew.timer" /etc/systemd/system/heapy-certbot-renew.timer
    proxy -d --name heapy-https --label com.heapy.component=https --restart unless-stopped >/dev/null
    docker exec heapy-https nginx -c /etc/heapy/nginx.conf -t
    curl -fsS --retry 5 --retry-connrefused --retry-delay 1 http://127.0.0.1/.well-known/acme-challenge/heapy-probe
    [[ $(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1/api/users/me) == 404 ]]
    systemctl daemon-reload
    echo 'HTTP 검증 경로 준비 완료. 인증서 발급은 사용자가 대화형 명령으로 진행해야 합니다.'
    exit 0
fi

[[ $(docker inspect --format '{{index .Config.Labels "com.heapy.component"}}' heapy-https) == https ]] || exit 1
openssl x509 -in /etc/letsencrypt/live/heapy-ip/cert.pem -checkend 86400 -noout
openssl verify -verify_ip 13.125.12.94 -CAfile /etc/pki/tls/certs/ca-bundle.crt \
    -untrusted /etc/letsencrypt/live/heapy-ip/chain.pem /etc/letsencrypt/live/heapy-ip/cert.pem
install -m 644 "$TARGET/nginx.conf" "$TARGET/nginx.previous.conf"
restore() {
    local status=$?
    trap - EXIT
    if [[ $status != 0 ]]; then
        install -m 644 "$TARGET/nginx.previous.conf" "$TARGET/nginx.conf"
        docker exec heapy-https nginx -c /etc/heapy/nginx.conf -s reload || true
        echo 'HTTPS 활성화 실패: 이전 Nginx 설정 복원.' >&2
    fi
    exit "$status"
}
trap restore EXIT
install -m 644 "$TARGET/nginx-https.conf" "$TARGET/nginx.conf"
docker exec heapy-https nginx -c /etc/heapy/nginx.conf -t
docker exec heapy-https nginx -c /etc/heapy/nginx.conf -s reload
sleep 2
[[ $(curl -sS --connect-to 13.125.12.94:443:127.0.0.1:443 -o /dev/null -w '%{http_code}' https://13.125.12.94/api/users/me) == 401 ]]
[[ $(curl -sS --connect-to 13.125.12.94:443:127.0.0.1:443 -o /dev/null -w '%{http_code}' https://13.125.12.94/actuator/health) == 404 ]]
systemctl enable --now heapy-certbot-renew.timer
systemctl is-active heapy-certbot-renew.timer
trap - EXIT
echo 'HTTPS 활성화 및 인증 필요 응답 확인 완료.'
