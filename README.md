# HEAPY 백엔드

건강검진·생활 기록·AI 상담·복약·미션·코디샵을 연결하는 HEAPY의 Spring Boot 서버입니다. 모바일 앱의 공개 API, 사용자 인증과 소유권 검증, 업무 규칙 및 최종 데이터 저장을 담당합니다.

[프로젝트 소개](https://github.com/Heapy-AI) · [모바일 앱](https://github.com/Heapy-AI/heapy-frontend) · [AI 서비스](https://github.com/Heapy-AI/heapy-ai-health)

## 주요 기능

- Supabase Auth 연동, JWT 검증, 사용자 프로필·온보딩 관리
- 건강검진 및 복약 문서 OCR 작업 요청, 검수와 확정 저장
- 삼성헬스 동기화 수신, 직접 입력 건강 기록과 영역별 추이 조회
- 홈 브리핑·건강 분석을 FastAPI에 요청하고 당일 결과 관리
- 복약 일정·복용 기록·Firebase 푸시 알림
- 건강 기록 기반 미션 추천과 진행률, 완료 보상 10코인
- 의상 구매·환불·착용, 코인 원장과 중복 요청 처리

## 기술 스택

Java 21 · Spring Boot 4.1.1 · Spring Security · Spring Data JPA/JDBC · PostgreSQL · Redis · Firebase Admin · AWS SDK

## 서비스 구조

```text
React Native → Nginx → Spring Boot
                         ├─ Supabase Auth / PostgreSQL
                         ├─ Redis: 분석 결과 캐시
                         ├─ FastAPI: AI 상담·건강 분석
                         ├─ AWS Lambda / S3: OCR
                         └─ Firebase: 복약 푸시
```

모바일 앱의 사용자별 요청은 이 서버를 거칩니다. AI 생성은 별도 FastAPI 서비스에서 수행하며, 내부 토큰과 필요한 사용자 문맥을 전달합니다.

## 로컬 실행

JDK 21과 프로젝트 스키마가 적용된 개발용 PostgreSQL이 필요합니다. Gradle은 저장소의 래퍼를 사용합니다. 빈 DB만 실행하면 `ddl-auto: validate` 검증을 통과하지 못합니다.

1. [.env.example](.env.example)의 항목을 참고해 IDE 실행 설정 또는 셸 환경변수에 값을 설정합니다.
2. 사용할 외부 연동의 설정을 추가합니다.
3. `local` 프로필로 실행합니다.

```powershell
# Windows PowerShell
$env:SPRING_PROFILES_ACTIVE = "local"
$env:OCR_ENABLED = "false"
.\gradlew.bat bootRun
```

```bash
# macOS / Linux
SPRING_PROFILES_ACTIVE=local OCR_ENABLED=false ./gradlew bootRun
```

Spring Boot가 루트 `.env` 파일을 자동으로 읽는 구성은 아닙니다. 파일을 복사하는 것만으로는 적용되지 않으므로 실행 환경에 변수를 주입해야 합니다. 위 예시는 OCR을 끈 상태이며 AI·푸시도 기본 비활성입니다.

| 설정 | 용도 |
|---|---|
| `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` | JDBC 연결과 DB 인증 |
| `SUPABASE_URL`, `SUPABASE_ANON_KEY` | 인증 서비스 연결 |
| `SUPABASE_JWT_ISSUER`, `SUPABASE_JWK_SET_URI`, `SUPABASE_JWT_AUDIENCE` | JWT 검증 |
| `CHAT_ENABLED`, `CHAT_BASE_URL`, `CHAT_INTERNAL_TOKEN` | 내부 AI 서비스 연결 |
| `HEALTH_ANALYSIS_ENABLED`, `HEALTH_REDIS_HOST`, `HEALTH_REDIS_PORT`, `HEALTH_REDIS_PASSWORD` | 건강 분석과 캐시 |
| `OCR_ENABLED`, `OCR_REGION`, `OCR_ACCOUNT_ID`, `OCR_BUCKET`, `OCR_WORKER_ARN` | OCR 실행 환경 |
| `PUSH_ENABLED`, `FIREBASE_PROJECT_ID`, `GOOGLE_APPLICATION_CREDENTIALS` | 복약 푸시와 서비스 계정 파일 |

외부 기능을 켤 때는 개발용 리소스와 자격증명을 사용합니다. 실제 비밀값·서비스 계정 JSON·운영 계정 식별자는 커밋하지 않습니다.

## API 문서

`local` 프로필에서 기본 포트는 `8080`입니다.

- Swagger UI: <http://localhost:8080/swagger-ui.html>
- OpenAPI: <http://localhost:8080/v3/api-docs>
- 상태 확인: <http://localhost:8080/actuator/health>

일반 설정에서는 Swagger가 비활성입니다. 사용자별 API는 유효한 액세스 토큰이 필요합니다. 실제 경로와 요청·응답 형식은 컨트롤러 및 실행 중인 OpenAPI를 기준으로 확인합니다.

## DB 변경

[supabase/migrations](supabase/migrations)에 SQL 마이그레이션을 관리합니다. Hibernate는 테이블을 자동 생성하지 않습니다. Flyway 의존성은 있지만 현재 설정과 배포 스크립트는 `FLYWAY_ENABLED=false`이며, 서버 재배포만으로 이 디렉터리의 SQL이 자동 적용되는 것은 아닙니다. 적용 대상 DB와 기존 이력을 확인한 뒤 팀의 DB 변경 절차로 적용합니다.

## 빌드와 배포

```powershell
.\gradlew.bat clean bootJar
```

```bash
./gradlew clean bootJar
docker build -t heapy-backend:local .
```

GitHub Actions가 컴파일과 이미지 빌드를 검증합니다. `dev` 푸시는 ECR 이미지 게시와 SSM을 통한 EC2 개발 서버 배포로 이어집니다. PR에서는 검증만 수행합니다. 세부 조건과 필요한 설정은 [.github/workflows](.github/workflows), [scripts/deploy](scripts/deploy)를 참고하세요.

테스트 소스는 현재 Git에서 제외되어 있으므로 새로 복제한 저장소에서 테스트 통과만으로 전체 기능 검증을 대신할 수 없습니다.

## 폴더 구조

| 경로 | 내용 |
|---|---|
| `src/main/java/com/heapy` | 기능별 API·서비스·저장소와 보안 설정 |
| `src/main/resources` | 실행 프로필과 애플리케이션 설정 |
| `supabase/migrations` | DB 변경 SQL |
| `scripts/deploy` | 배포·진단 도구 |
| `.github/workflows` | 빌드 검증 및 개발 배포 |

## 협업과 문서

기능 브랜치에서 작업한 뒤 `dev`로 PR을 보냅니다. README는 공유 문서이므로 ignore 예외로 유지합니다. 로컬 `Reference`의 API 명세·시스템 아키텍처·화면 요구사항·DB 설계는 Git에 포함되지 않으므로 팀 공유 경로에서 별도로 전달받습니다.

<!-- 작성자: 김진우 -->
