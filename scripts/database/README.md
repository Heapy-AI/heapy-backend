# 검진 버전 2 적용 준비

> 2026-09-09 정정: 공유 DB 적용과 백엔드·OCR dev 배포를 완료했다. CLI 암묵적 트랜잭션안은 로컬 검증에서 실패하여 사용하지 않았다. 실제 적용은 psql 명시적 단일 트랜잭션에 마이그레이션 이력 등록까지 포함했다. 최신 결과는 docs/api/검진_버전2_DB적용_개발배포_결과.md와 docs/requests/검진_버전2_실행방식_검증보완.md를 따른다. 아래 2026-09-08 현황은 적용 전 기록이다. OCR 버전 2 생성은 비활성이다.

- 작성자: 김진우
- 기존 마이그레이션은 수정하지 않는다. 이 도구는 SQL 생성만 하며 DB에 접속하지 않는다.

## 적용 SQL 생성

저장소 루트에서 실행한다. 출력 파일은 이미 존재하면 덮어쓰지 않는다.

```powershell
python scripts/database/prepare_findings_migration.py --output checkup-v2-apply.sql
```

위 결과는 BEGIN/COMMIT을 포함한다. 전체 트랜잭션을 자체 관리하는 승인된 마이그레이션 도구에 전달할 때만 `--managed-transaction`을 사용한다. 기존 SQL의 SHA-256이 검토값과 다르면 생성을 중단한다.

두 경우 모두 대상 마스터·검진 결과 테이블에 SHARE ROW EXCLUSIVE 잠금을 먼저 잡는다. 잠금 대기 5초, 문장 실행 60초, 트랜잭션 유휴 60초 제한을 설정한다. 제한은 각 문장·유휴 구간에 적용되며 전체 작업의 벽시계 제한은 아니다. 트랜잭션 종료 시 잠금이 풀린다. 챗봇도 같은 DB를 사용하므로 점검 시간에 검진 관련 쓰기 영향을 고려한다.

원본 SQL의 객체 생성과 마스터 사전 조건을 모두 같은 세션·트랜잭션으로 실행해야 한다. SQL 생성은 적용 승인 또는 마이그레이션 이력 등록을 의미하지 않는다. 적용 직전 대상·기존 데이터·역할·복구 조건을 다시 확인한다. 실제 적용 도구의 이력 등록 절차까지 확인하고 이 마이그레이션 한 건만 적용한다.

## 실행 컨테이너 접속 대상 점검

해당 진단 파일이 준비된 EC2에서 Docker 조회 권한으로 실행한다.

```bash
sudo python3 scripts/deploy/inspect_database_target.py --expected-project panaspvsdszeapkoltna
```

출력은 DB·인증 프로젝트 일치 여부, DB 프로젝트 식별 성공 여부, 역할명, JDBC URL 내부 자격증명 유무만 포함한다. 전체 환경변수·URL·비밀번호·토큰은 출력하지 않는다. 알 수 없는 접속 형식은 프로젝트 일치를 추정하지 않는다. 이 검사는 컨테이너 설정 대조이며 실제 SQL 연결·권한 또는 DB 백업 검증을 대신하지 않는다. 출력이 실패하면 docker inspect 원문을 채팅에 붙이지 않는다.

## 테스트와 현황

```powershell
python -m unittest discover -s scripts/database/tests -v
```

2026-09-08 검증:

- 준비 도구 테스트 3개 통과.
- OCR의 실제 분류 파서·매핑 함수로 만든 합성 스냅샷을 `src/test/resources/ocr-v2-synthetic.json`에 저장했다. 실제 문서와 무관한 합성 자료다.
- `OcrProducerContractTest` 2개: 일반 결과·검사 소견·종합소견 확정 검증, 미매칭 명시적 제외 누락 거부.
- `test bootJar --offline --no-daemon --rerun-tasks`: 성공. 103개 중 97개 통과, 실패/오류 0, 로컬 PostgreSQL 환경 미설정으로 6개 건너뜀.
- 원격 DB는 마스터와 관련 결과 건수만 읽었다. 신규 소견 구조 미생성, PA 결과 0건을 재확인했다. 개인 건강값은 조회하지 않았다.
- 공유 DB 적용·EC2 진단 실행·백업 확인·푸시·배포는 미실행이다. 기존 배포 진단·검진 구현 변경은 보존했다.

## 근거

Reference·Reference/rule·AGENTS.md 파일은 없어 대화 규칙과 기존 코드 형식을 적용했다. API 명세·시스템 아키텍처·요구사항·DB 설계의 저장 확장 절 및 아래 문서를 대조했다.

- docs/api/검진_저장확장_공유계약.md 1~9절. 버전 3 checkupContexts는 미구현 범위 유지.
- docs/database/검진_소견저장_마이그레이션_인계.md
- supabase/migrations/20260908085751_checkup_findings.sql
- [PostgreSQL 17 잠금 문서](https://www.postgresql.org/docs/17/explicit-locking.html)

DB 적용 승인은 환경·접속 역할·복구 수단 확인 후 진행한다. 새 OCR 결과 활성화는 DB·백엔드·프런트 준비 후 마지막에 수행한다.
