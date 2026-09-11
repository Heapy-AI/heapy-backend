# HEAPY v1 백엔드 API 명세서

> 문서 형식: Swagger/OpenAPI v3 스타일 Markdown  
> 프로젝트: HEAPY — AI 건강관리 서비스  
> 작성자: 김진우  
> 작성일: 2026-09-03  
> 문서 버전: v1.1  
> 상태: v1 구현 기준 확정본  
> 공개 Base URL: /api  
> 내부 AI Base URL: /internal  
> URL 버전 정책: 경로에 `/v1`과 같은 버전 문자열을 사용하지 않음

---

# 1부. 시스템 아키텍처 및 DB 요약

## 1.1 시스템 구성

~~~text
모바일 앱
  └─ Spring Boot 통합 공개 API (/api)
      ├─ Auth 파사드
      │   └─ Supabase Auth 이메일 인증·세션 처리
      ├─ Supabase JWT 검증
      ├─ 사용자·리소스 소유권 검증
      ├─ 업무 트랜잭션·멱등성
      ├─ Supabase PostgreSQL
      └─ FastAPI 내부 AI API (/internal)
          ├─ 건강검진·복약 OCR
          ├─ 건강 브리핑
          └─ 상담·RAG·SSE
~~~

- 모바일 앱은 Spring Boot `/api/auth/**`만 호출하며 Spring Boot가 Supabase Auth에 이메일 인증 처리를 위임한다.
- 모바일 앱은 FastAPI, PostgreSQL, service role key에 직접 접근하지 않는다.
- Spring Boot는 Access Token의 서명·만료·발급자·대상을 검증하고 subject를 사용자 ID로 사용한다.
- Spring Boot 소유권 검사와 Supabase RLS를 함께 적용한다.
- FastAPI는 외부에 공개하지 않고 Spring Boot만 내부 인증을 거쳐 호출한다.
- 개인 건강·복약·대화 데이터는 공용 RAG 캐시나 Pinecone에 저장하지 않는다.

## 1.2 인증 책임

| 기능 | 담당 | Spring Boot 공개 API |
|---|---|---|
| 이메일 회원가입·로그인 | Spring Boot → Supabase Auth | POST /api/auth/signup, POST /api/auth/login |
| Access Token 갱신 | Spring Boot → Supabase Auth | POST /api/auth/token/refresh |
| 세션·다음 진입 단계 확인 | Spring Boot | GET /api/auth/session |
| 전체 세션 로그아웃 | Spring Boot → Supabase Auth | POST /api/auth/logout |
| 이메일 인증 메일 재발송 | Spring Boot → Supabase Auth | POST /api/auth/email/verification/resend |
| 비밀번호 재설정 | Spring Boot → Supabase Auth | POST /api/auth/password/reset-request, POST /api/auth/password/reset-confirm |
| 로그인 사용자 프로필 확인 | Spring Boot | GET /api/users/me |
| 기기 푸시 토큰 비활성화 | Spring Boot | DELETE /api/devices/push-tokens/{deviceTokenId} |

로그아웃은 1차 출시의 단일 기기 사용 가정에 따라 계정의 전체 Refresh Token 세션을 폐기한다. 앱은 현재 기기의 푸시 토큰을 먼저 비활성화한다. 권한 판단에는 사용자가 수정할 수 있는 `user_metadata`를 사용하지 않는다.

## 1.3 DB 도메인

| 도메인 | 주요 테이블 |
|---|---|
| 사용자·약관 | users, terms, user_terms_consents |
| 건강검진·OCR | master_checkup_item, ocr_jobs, ocr_correction_logs, health_checkup_records, health_checkup_results |
| 생활 건강 | health_data_connections, health_sync_runs, lifestyle_activity, lifestyle_exercise, lifestyle_bio, lifestyle_nutrition, lifestyle_water_intake, lifestyle_sleep |
| 홈·분석 | user_home_modules, health_metric_policies, daily_health_briefings, health_alerts |
| 복약·알림 | user_medications, medication_schedules, medication_intakes, medication_ocr_results, private.device_tokens, notifications |
| 미션·코디 | mission_catalog, mission_rules, user_missions, mission_progress_events, mission_feedback, coin_ledger, shop_items, shop_purchases, user_inventory, equipped_items |
| 상담 | chat_sessions, chat_messages, chat_message_citations, chat_suggested_actions |

---

# 2부. 공통 API 계약

## 2.1 인증·요청 헤더

| 헤더 | 값 | 적용 |
|---|---|---|
| Authorization | Bearer {supabase_access_token} | 회원가입·로그인·인증 메일·비밀번호 재설정을 제외한 공개 API 필수 |
| Accept | application/json | 일반 API 필수 |
| X-Request-Id | 요청 추적 UUID | 선택 |
| Idempotency-Key | UUID | 재시도 가능한 쓰기 필수 |

같은 사용자·엔드포인트·Idempotency-Key의 동일 요청은 최초 성공 응답을 재사용한다. 같은 키로 다른 본문을 보내면 409 IDEMPOTENCY_KEY_REUSED를 반환한다. 적용 대상은 회원가입, 토큰 갱신, 로그아웃, 약관 동의, 온보딩 완료, OCR 생성·확정, 건강 데이터 동기화·직접 입력, 복약 등록·완료·건너뜀, 푸시 토큰 upsert, 미션 상태 변경·완료, 구매·취소, 상담 요청과 AI 제안 행동 승인이다.

## 2.2 성공 응답

~~~json
{
  "success": true,
  "data": {},
  "message": "요청이 처리되었습니다.",
  "meta": null
}
~~~

- data는 객체, 배열 또는 null이다.
- meta는 페이지네이션·집계 기준 등 부가정보에 사용한다.
- 204 No Content에는 본문이 없다.
- SSE에는 공통 JSON 봉투를 적용하지 않는다.

## 2.3 오류 응답

~~~json
{
  "success": false,
  "timestamp": "2026-09-03T06:20:00.000Z",
  "status": 400,
  "code": "COMMON-001",
  "message": "입력값이 올바르지 않습니다.",
  "errors": [
    {
      "field": "birthDate",
      "value": "2099-01-01",
      "reason": "생년월일은 오늘 이후일 수 없습니다."
    }
  ],
  "path": "/api/users/me/profile",
  "traceId": "01J7HEAPY9V4K6E8WQ3N2M1ABC"
}
~~~

내부 예외명, SQL, 토큰, 개인 건강 원문은 오류 응답과 운영 로그에 포함하지 않는다.

## 2.4 공통 HTTP 상태

| HTTP | 의미 |
|---:|---|
| 200 | 조회·변경 성공 |
| 201 | 생성 성공 |
| 202 | 비동기 작업 접수·처리 중 |
| 204 | 삭제·비활성화 성공 |
| 400 | 형식·값 오류 |
| 401 | 토큰 없음·만료·검증 실패 |
| 403 | 이메일 미인증 또는 허용되지 않은 서버 기능 |
| 404 | 없거나 요청 사용자 소유가 아닌 리소스 |
| 409 | 중복·상태·멱등성 충돌 |
| 410 | 만료된 리소스 |
| 413 | 파일·배치 제한 초과 |
| 415 | 지원하지 않는 미디어 |
| 422 | 업무 규칙 검증 실패 |
| 429 | 요청 제한 초과 |
| 500 | 서버 내부 오류 |
| 503 | 내부 AI·외부 공급자 장애 |

## 2.5 페이지네이션

| 쿼리 | 타입 | 필수 | 기본값 | 제약 |
|---|---|:---:|---|---|
| cursor | string | - | 없음 | 서버 발급 불투명 문자열 |
| limit | integer | - | 20 | 1~100 |

~~~json
{
  "success": true,
  "data": [],
  "message": "조회되었습니다.",
  "meta": {
    "nextCursor": "eyJsYXN0SWQiOiIuLi4ifQ",
    "hasNext": true,
    "limit": 20
  }
}
~~~

## 2.6 필드·시간 규칙

- JSON은 lowerCamelCase, DB는 snake_case를 사용한다.
- UUID는 하이픈 포함 문자열이다.
- 절대시각은 ISO 8601 UTC, 날짜는 YYYY-MM-DD, 시각은 HH:mm:ss다.
- 생활 건강 집계·오늘 미션·브리핑 날짜는 Asia/Seoul 기준이다.
- 선택값 부재는 null이며 빈 문자열로 대체하지 않는다.
- 응답에 내부 userId, idempotencyKey, 민감 토큰을 불필요하게 노출하지 않는다.

## 2.7 권한·RLS

- 경로나 본문의 userId를 신뢰하지 않고 JWT subject를 사용한다.
- 다른 사용자 리소스는 404 RESOURCE_NOT_FOUND를 반환한다.
- public 사용자 소유 테이블은 RLS와 명시적 GRANT를 각각 검증한다.
- UPDATE에는 SELECT 정책, USING, WITH CHECK가 모두 필요하다.
- private.device_tokens는 Spring Boot만 접근한다.
- service role key는 모바일·응답·로그에 노출하지 않는다.

## 2.8 비동기 상태

| 상태 | 의미 |
|---|---|
| pending | 접수됨 |
| running | 처리 중 |
| completed | 성공 |
| failed | 실패 |
| expired | 미확정 상태로 만료 |

---

# 3부. 인증 API

> Base URL: `/api/auth`  
> 인증 공급자: Supabase Auth  
> 공개 책임: Spring Boot Auth 파사드  
> 1차 출시 방식: 이메일·비밀번호

## Tag: Authentication

### POST /api/auth/signup

**Summary**: 이메일 회원가입 및 인증 메일 발송

**Description**:

이메일과 비밀번호로 Supabase Auth 계정을 만들고 동일 UUID의 `public.users` 프로필 초기 행을 생성한다. 이메일 인증 전에는 정상 Access Token과 Refresh Token을 발급하지 않는다.

**Authentication**: 불필요

**Request Body** (`application/json`)

| 필드 | 타입 | 필수 | 제약 |
|---|---|:---:|---|
| email | string | O | 이메일 형식, 공백 제거 후 소문자 정규화, 최대 320자 |
| password | string | O | 8~256자, 비밀번호 정책 충족 |

~~~json
{
  "email": "heapy@example.com",
  "password": "SecurePassword123!"
}
~~~

`201 Created`

~~~json
{
  "success": true,
  "data": {
    "userId": "9cf0cf52-b838-4f29-9756-858d45038ca5",
    "email": "heapy@example.com",
    "emailVerificationRequired": true,
    "verificationEmailSent": true,
    "nextStep": "emailVerification"
  },
  "message": "인증 메일을 발송했습니다.",
  "meta": null
}
~~~

**업무 규칙**

- 이메일 존재 여부를 불필요하게 노출하지 않는다.
- `auth.users.id`와 `public.users.user_id`는 같은 UUID를 사용한다.
- 비밀번호와 인증 토큰은 업무 테이블에 저장하지 않는다.
- 이메일 인증 링크는 앱 딥링크로 연결한다.

**DB·외부 매핑**: Supabase `auth.users`, `public.users`  
**Errors**: 400 COMMON-001, 409 AUTH-005, 422 AUTH-006, 429 AUTH-008, 503 AUTH-009

---

### POST /api/auth/login

**Summary**: 이메일·비밀번호 로그인

**Description**:

Supabase Auth에서 자격 증명을 검증하고 모바일 세션 토큰을 발급한다. Spring Boot가 필수 약관과 온보딩 상태를 조회해 앱의 다음 진입 화면을 `nextStep`으로 반환한다.

**Authentication**: 불필요

**Request Body** (`application/json`)

| 필드 | 타입 | 필수 | 제약 |
|---|---|:---:|---|
| email | string | O | 이메일 형식, 소문자 정규화 |
| password | string | O | 8~256자 |

~~~json
{
  "email": "heapy@example.com",
  "password": "SecurePassword123!"
}
~~~

`200 OK`

~~~json
{
  "success": true,
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiIs...",
    "refreshToken": "v1.eyJhbGciOiJIUzI1NiIs...",
    "tokenType": "Bearer",
    "expiresIn": 3600,
    "expiresAt": "2026-09-03T10:30:00Z",
    "user": {
      "userId": "9cf0cf52-b838-4f29-9756-858d45038ca5",
      "email": "heapy@example.com",
      "emailVerified": true
    },
    "nextStep": "terms",
    "onboardingStep": 1
  },
  "message": "로그인되었습니다.",
  "meta": null
}
~~~

| nextStep | 조건 | 앱 이동 |
|---|---|---|
| emailVerification | 이메일 미인증 | 이메일 인증 안내 |
| terms | 현재 필수 약관 미동의 | 약관 동의 |
| profile | 필수 프로필 미완료 | onboardingStep 다음 프로필 단계 |
| home | 필수 약관·프로필 완료 | 홈 |

`401 Unauthorized`

~~~json
{
  "success": false,
  "timestamp": "2026-09-03T09:30:00Z",
  "status": 401,
  "code": "AUTH-001",
  "message": "이메일 또는 비밀번호를 확인해 주세요.",
  "errors": [],
  "path": "/api/auth/login",
  "traceId": "01K4JYQ9XQ5N0J2P9W9R6H4QKS"
}
~~~

`403 Forbidden`은 이메일 미인증 상태로 `AUTH-002`를 반환한다.

**보안 규칙**

- Access Token과 Refresh Token은 모바일 Keychain 또는 Keystore에 저장한다.
- 토큰·비밀번호는 응답 추적 로그와 오류 로그에 기록하지 않는다.
- 계정 존재 여부를 숨기기 위해 이메일·비밀번호 오류 메시지를 통일한다.

**DB·외부 매핑**: Supabase `auth.users`, `users`, `terms`, `user_terms_consents`  
**Errors**: 400 COMMON-001, 401 AUTH-001, 403 AUTH-002, 429 AUTH-008, 503 AUTH-009

---

### GET /api/auth/session

**Summary**: 현재 세션과 다음 진입 단계 조회

**Authentication**: Bearer Token

`200 OK`

~~~json
{
  "success": true,
  "data": {
    "authenticated": true,
    "user": {
      "userId": "9cf0cf52-b838-4f29-9756-858d45038ca5",
      "email": "heapy@example.com",
      "emailVerified": true
    },
    "nextStep": "profile",
    "onboardingStep": 3,
    "accessTokenExpiresAt": "2026-09-03T10:30:00Z"
  },
  "message": "세션을 확인했습니다.",
  "meta": null
}
~~~

- 앱 시작 시 저장된 Access Token으로 호출한다.
- 만료된 Access Token이면 401을 반환하고 앱은 Refresh API를 한 번 호출한다.
- Refresh도 실패하면 토큰을 삭제하고 로그인 화면으로 이동한다.

**DB·외부 매핑**: Supabase Auth, `users`, `terms`, `user_terms_consents`  
**Errors**: 401 AUTH-003

---

### POST /api/auth/token/refresh

**Summary**: Access Token과 Refresh Token 회전

**Authentication**: 불필요

~~~json
{
  "refreshToken": "v1.eyJhbGciOiJIUzI1NiIs..."
}
~~~

`200 OK`

~~~json
{
  "success": true,
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiIs...",
    "refreshToken": "v1.rotated-refresh-token...",
    "tokenType": "Bearer",
    "expiresIn": 3600,
    "expiresAt": "2026-09-03T11:30:00Z"
  },
  "message": "세션을 갱신했습니다.",
  "meta": null
}
~~~

- 성공 시 기존 Refresh Token을 폐기하고 새 토큰으로 교체한다.
- 같은 Refresh Token으로 동시에 요청하면 최초 성공 외 요청은 401 AUTH-004다.

**DB·외부 매핑**: Supabase Auth 세션  
**Errors**: 400 COMMON-001, 401 AUTH-004, 429 AUTH-008, 503 AUTH-009

---

### POST /api/auth/logout

**Summary**: 로그아웃 및 전체 세션 종료

**Authentication**: Bearer Token

`204 No Content`

- 1차 출시는 한 기기 사용을 가정하므로 계정의 모든 Refresh Token 세션을 폐기한다.
- 앱은 호출 전에 현재 기기의 푸시 토큰을 `DELETE /api/devices/push-tokens/{deviceTokenId}`로 비활성화한다.
- 로그아웃 성공 또는 이미 폐기된 세션은 동일하게 204를 반환한다.
- 앱은 응답 후 로컬 Access Token과 Refresh Token을 삭제한다.

**DB·외부 매핑**: Supabase Auth 세션  
**Errors**: 401 AUTH-003, 503 AUTH-009

---

### POST /api/auth/email/verification/resend

**Summary**: 이메일 인증 메일 재발송

**Authentication**: 불필요

~~~json
{
  "email": "heapy@example.com"
}
~~~

`200 OK`

~~~json
{
  "success": true,
  "data": {
    "verificationEmailSent": true,
    "retryAfterSeconds": 60
  },
  "message": "계정 상태와 관계없이 요청을 접수했습니다.",
  "meta": null
}
~~~

계정 존재 여부와 이미 인증됐는지를 응답으로 구분하지 않는다. 요청 제한 초과는 429 AUTH-008이다.

---

### POST /api/auth/password/reset-request

**Summary**: 비밀번호 재설정 메일 요청

**Authentication**: 불필요

~~~json
{
  "email": "heapy@example.com"
}
~~~

`200 OK`

~~~json
{
  "success": true,
  "data": {
    "resetEmailAccepted": true
  },
  "message": "계정 상태와 관계없이 요청을 접수했습니다.",
  "meta": null
}
~~~

---

### POST /api/auth/password/reset-confirm

**Summary**: 비밀번호 재설정 완료

**Authentication**: 재설정 전용 토큰

| 필드 | 타입 | 필수 | 제약 |
|---|---|:---:|---|
| resetToken | string | O | 딥링크에서 받은 일회성 토큰 |
| newPassword | string | O | 8~256자, 비밀번호 정책 충족 |

~~~json
{
  "resetToken": "recovery-token...",
  "newPassword": "NewSecurePassword123!"
}
~~~

`204 No Content`

- 성공 시 기존 로그인 세션을 모두 폐기하고 다시 로그인하게 한다.
- 만료·사용 완료 토큰은 410 AUTH-007이다.

**DB·외부 매핑**: Supabase Auth  
**Errors**: 400 COMMON-001, 410 AUTH-007, 422 AUTH-006, 429 AUTH-008, 503 AUTH-009

---

# 4부. 사용자·온보딩·약관 API

## Tag: Me

### GET /api/users/me

**Summary**: 로그인 사용자와 온보딩 상태 조회

**Authentication**: Bearer Token

**Responses**

200 OK

~~~json
{
  "success": true,
  "data": {
    "userId": "9cf0cf52-b838-4f29-9756-858d45038ca5",
    "name": "김히피",
    "birthDate": "1995-04-12",
    "sex": "Female",
    "heightCm": 165.4,
    "weightKg": 58.2,
    "smokingStatus": "never",
    "alcoholFrequency": "monthly_1_2",
    "chronicConditions": [],
    "allergies": [],
    "healthCautions": null,
    "onboardingStep": 6,
    "onboardingCompleted": true,
    "onboardingCompletedAt": "2026-09-01T04:10:00Z",
    "requiredConsentCompleted": true
  },
  "message": "사용자 정보를 조회했습니다.",
  "meta": null
}
~~~

**DB 매핑**: users, terms, user_terms_consents

**Errors**: 401 AUTH-003

---

### PATCH /api/users/me/profile

**Summary**: 온보딩 단계별 프로필 저장 또는 완료 후 수정

**Request Body**: application/json, 부분 업데이트

| 필드 | 타입 | 필수 | 제약 |
|---|---|:---:|---|
| name | string | - | 공백 제거 후 1~50자 |
| birthDate | date | - | 오늘 이후 불가 |
| sex | string | - | Male, Female |
| heightCm | number | - | 30~250 |
| weightKg | number | - | 2~500 |
| smokingStatus | string | - | never, former, current |
| alcoholFrequency | string | - | none, monthly_1_2, weekly_1_2, weekly_3_plus |
| chronicConditions | object[] | - | canonicalKey, displayName, source |
| allergies | object[] | - | canonicalKey, displayName, source |
| healthCautions | string 또는 null | - | 최대 1000자 |
| onboardingStep | integer | O | 1~6, 역행 불가 |

~~~json
{
  "heightCm": 165.4,
  "weightKg": 58.2,
  "smokingStatus": "never",
  "alcoholFrequency": "monthly_1_2",
  "onboardingStep": 4
}
~~~

200 OK는 GET /me와 같은 최신 프로필을 반환한다.

**업무 규칙**

- 다음 버튼마다 저장하며 이어쓰기 기준은 서버 값이다.
- 이 API는 부분 저장만 수행하며 온보딩 완료를 자동 확정하지 않는다.
- 알레르기·건강 주의사항이 없어도 완료할 수 있다.
- 완료 전 프로필은 건강 분석·추천에 사용하지 않는다.

**DB 매핑**: users

**Errors**: 400 COMMON-001, 409 ONBOARDING-001

---

### POST /api/users/me/onboarding/complete

**Summary**: 온보딩 필수값 검증 및 완료 확정

**Authentication**: Bearer Token  
**Headers**: Idempotency-Key 필수

`200 OK`

~~~json
{
  "success": true,
  "data": {
    "onboardingCompleted": true,
    "onboardingStep": 6,
    "onboardingCompletedAt": "2026-09-03T06:20:00Z",
    "nextStep": "home"
  },
  "message": "프로필 설정을 완료했습니다.",
  "meta": null
}
~~~

- 이름·생년월일·성별·키·몸무게·흡연·음주·건강 배경과 현재 필수 약관 동의를 한 트랜잭션에서 검증한다.
- 알레르기와 건강 주의사항은 비어 있어도 완료할 수 있다.
- 필수값 누락 시 422 ONBOARDING-002와 누락 필드 목록을 반환한다.

**DB 매핑**: users, terms, user_terms_consents  
**Errors**: 401 AUTH-003, 409 ONBOARDING-001, 422 ONBOARDING-002

---

### GET /api/users/me/dashboard

**Summary**: 마이 화면 요약 조회

~~~json
{
  "success": true,
  "data": {
    "profile": {"name": "김히피", "onboardingCompleted": true},
    "healthConnection": {
      "provider": "samsung_health",
      "status": "connected",
      "lastSyncedAt": "2026-09-03T05:30:00Z"
    },
    "checkup": {
      "latestRecordId": "4076edb6-c9bc-403b-b951-d287f78c609d",
      "measuredAt": "2026-08-12"
    },
    "medication": {"activeCount": 2, "pendingTodayCount": 1}
  },
  "message": "마이 화면을 조회했습니다.",
  "meta": null
}
~~~

**DB 매핑**: users, health_data_connections, health_checkup_records, user_medications, medication_intakes

## Tag: Terms

### GET /api/terms

**Summary**: 현재 효력이 있는 약관 조회

**Query**: includeOptional(boolean, 기본 true)

~~~json
{
  "success": true,
  "data": [
    {
      "termsId": 10,
      "termsCode": "sensitive_health",
      "version": "1.0",
      "title": "민감 건강정보 처리 동의",
      "contentUrl": "https://example.heapy.app/terms/sensitive-health/1.0",
      "contentHash": "sha256:...",
      "required": true,
      "effectiveAt": "2026-09-01T00:00:00Z",
      "consentStatus": "not_agreed"
    }
  ],
  "message": "약관을 조회했습니다.",
  "meta": null
}
~~~

**DB 매핑**: terms, user_terms_consents

---

### POST /api/users/me/consents

**Summary**: 약관 동의·철회 이벤트 저장

**Headers**: Idempotency-Key 필수

~~~json
{
  "consents": [
    {"termsId": 10, "action": "agreed"},
    {"termsId": 11, "action": "agreed"},
    {"termsId": 12, "action": "revoked"}
  ],
  "consentSource": "app"
}
~~~

201 Created

~~~json
{
  "success": true,
  "data": {
    "consents": [
      {"consentId": 120, "termsId": 10, "action": "agreed", "occurredAt": "2026-09-03T06:20:00Z"},
      {"consentId": 121, "termsId": 11, "action": "agreed", "occurredAt": "2026-09-03T06:20:00Z"},
      {"consentId": 122, "termsId": 12, "action": "revoked", "occurredAt": "2026-09-03T06:20:00Z"}
    ],
    "requiredConsentCompleted": true,
    "nextStep": "profile"
  },
  "message": "약관 동의 상태를 저장했습니다.",
  "meta": null
}
~~~

- 동의 목록 전체를 검증한 뒤 하나의 트랜잭션으로 이벤트 이력을 추가한다.
- 동의 이력은 기존 행을 수정하지 않는다.
- 서비스 이용 중 필수 약관 철회는 409 TERMS-002다.

**DB 매핑**: terms, user_terms_consents

### 프로필 선택 코드 관리

질환·알레르기·음주 빈도 선택지는 앱 고정 코드와 이 명세의 열거형으로 관리한다. 1차 출시에는 별도 기준정보 API나 카탈로그 테이블을 만들지 않는다. 음주 빈도는 `none`, `monthly_1_2`, `weekly_1_2`, `weekly_3_plus`를 사용한다. 질환·알레르기는 API에서 `{canonicalKey, displayName, source}` 형식을 사용하고 DB에는 각각 `{canonical_key, display_name, source}` JSONB 배열로 저장한다. 정규화되지 않은 직접 입력은 `canonicalKey=null`, `source=user`로 원문 표시명을 보존한다.

---

# 5부. 건강검진·OCR API

## Tag: Checkups

### POST /api/checkups/ocr-jobs

**Summary**: 건강검진 이미지·PDF 업로드 및 OCR 작업 생성

**Content-Type**: multipart/form-data  
**Headers**: Idempotency-Key 필수

| 파트 | 타입 | 필수 | 제약 |
|---|---|:---:|---|
| file | binary | O | jpg, jpeg, png, pdf, 최대 20MB |
| inputType | string | O | camera, image, pdf |

PDF는 최대 20페이지이며 Base64 JSON 업로드는 허용하지 않는다.

202 Accepted

~~~json
{
  "success": true,
  "data": {
    "jobId": "871f28e6-aec7-41fc-9d5d-02041b3d0d1a",
    "documentType": "health_checkup",
    "status": "pending",
    "expiresAt": "2026-09-04T06:20:00Z",
    "pollAfterMs": 1500
  },
  "message": "건강검진 OCR 작업을 접수했습니다.",
  "meta": null
}
~~~

- 원본·변환 이미지는 OCR 처리 종료 후 즉시 삭제한다.
- DB에는 파일 경로와 전체 OCR 결과를 저장하지 않는다.

**DB 매핑**: ocr_jobs  
**Errors**: 413 OCR-001, 415 OCR-002, 503 AI-001

---

### GET /api/checkups/ocr-jobs/{jobId}

**Summary**: 건강검진 OCR 상태·임시 결과 조회

~~~json
{
  "success": true,
  "data": {
    "jobId": "871f28e6-aec7-41fc-9d5d-02041b3d0d1a",
    "status": "completed",
    "pageCount": 2,
    "result": {
      "measuredAt": "2026-08-12",
      "providerName": "히피건강검진센터",
      "items": [
        {
          "fieldKey": "result-1",
          "itemCode": "fasting_glucose",
          "itemName": "공복혈당",
          "value": "102",
          "numericValue": 102,
          "unit": "mg/dL",
          "status": "경계",
          "confidence": 0.96
        }
      ]
    },
    "expiresAt": "2026-09-04T06:20:00Z"
  },
  "message": "OCR 작업을 조회했습니다.",
  "meta": null
}
~~~

result는 제한된 임시 저장소에서만 반환하며 확정·이탈·만료 시 제거한다.

**DB 매핑**: ocr_jobs, master_checkup_item  
**Errors**: 404 RESOURCE_NOT_FOUND, 410 OCR-003

---

### POST /api/checkups/ocr-jobs/{jobId}/confirm

**Summary**: 검수한 최종 건강검진 결과 확정

**Headers**: Idempotency-Key 필수

~~~json
{
  "measuredAt": "2026-08-12",
  "providerName": "히피건강검진센터",
  "results": [
    {
      "itemCode": "fasting_glucose",
      "value": "100",
      "numericValue": 100,
      "unit": "mg/dL",
      "status": "정상"
    }
  ],
  "corrections": [
    {
      "fieldKey": "result-1.value",
      "itemCode": "fasting_glucose",
      "originalValue": "102",
      "correctedValue": "100",
      "correctionType": "value"
    }
  ]
}
~~~

201 Created

~~~json
{
  "success": true,
  "data": {
    "recordId": "4076edb6-c9bc-403b-b951-d287f78c609d",
    "sourceType": "ocr",
    "resultCount": 1,
    "confirmedAt": "2026-09-03T06:25:00Z"
  },
  "message": "건강검진 결과를 확정했습니다.",
  "meta": null
}
~~~

**트랜잭션**: 소유권·작업 상태 검증 → 검진 회차 생성 → 결과 일괄 생성 → 실제 수정 로그 생성 → 작업 확정. 같은 jobId는 한 번만 확정한다.

**DB 매핑**: ocr_jobs, ocr_correction_logs, health_checkup_records, health_checkup_results  
**Errors**: 409 OCR-004, 410 OCR-003, 422 OCR-005

---

### GET /api/checkups

**Summary**: 건강검진 회차 목록 조회

**Query**: cursor, limit

각 항목은 recordId, measuredAt, providerName, sourceType, resultCount, confirmedAt을 반환한다. 정렬은 measuredAt DESC, recordId DESC다.

---

### GET /api/checkups/{recordId}

**Summary**: 확정 건강검진 상세 조회

~~~json
{
  "success": true,
  "data": {
    "recordId": "4076edb6-c9bc-403b-b951-d287f78c609d",
    "measuredAt": "2026-08-12",
    "providerName": "히피건강검진센터",
    "results": [
      {
        "itemCode": "fasting_glucose",
        "itemName": "공복혈당",
        "value": "100",
        "numericValue": 100,
        "unit": "mg/dL",
        "status": "정상"
      }
    ]
  },
  "message": "건강검진 상세를 조회했습니다.",
  "meta": null
}
~~~

기관이 저장한 status를 그대로 반환하며 앱이 재진단하지 않는다.

---

### GET /api/checkups/comparison

**Summary**: 건강검진 회차별 항목 비교

| 쿼리 | 타입 | 필수 | 제약 |
|---|---|:---:|---|
| recordIds | UUID[] | O | 쉼표 구분, 2~5개 |
| itemCodes | string[] | - | 미지정 시 공통 항목 |

비교 가능한 회차가 두 개 미만이면 422 CHECKUP-002다.

---

# 6부. Samsung Health·생활 건강 API

## Tag: Health Connections

### GET /api/health-connections

**Summary**: 내 Samsung Health 연결·권한 상태 조회

~~~json
{
  "success": true,
  "data": [
    {
      "connectionId": "b72f63be-b0f5-49b0-945a-24dedb3a4838",
      "provider": "samsung_health",
      "deviceInstallationId": "f64ee285-7ec2-48e0-bd0d-31dc759531f9",
      "status": "connected",
      "grantedDataTypes": ["sleep", "heart_rate", "steps", "water"],
      "sdkVersion": "1.0.0",
      "lastPermissionCheckedAt": "2026-09-03T05:20:00Z",
      "lastSyncedAt": "2026-09-03T05:30:00Z"
    }
  ],
  "message": "건강 데이터 연결 상태를 조회했습니다.",
  "meta": null
}
~~~

**DB 매핑**: health_data_connections

---

### POST /api/health-connections/samsung

**Summary**: Samsung Health 앱 설치 단위 연결 등록·갱신

**Headers**: Idempotency-Key 필수

~~~json
{
  "deviceInstallationId": "f64ee285-7ec2-48e0-bd0d-31dc759531f9",
  "grantedDataTypes": [
    "sleep",
    "heart_rate",
    "blood_glucose",
    "blood_pressure",
    "body_composition",
    "exercise",
    "floors",
    "steps",
    "activity",
    "water",
    "nutrition"
  ],
  "sdkVersion": "1.0.0",
  "permissionCheckedAt": "2026-09-03T05:20:00Z"
}
~~~

신규는 201 Created, 같은 설치 단위 갱신은 200 OK로 최신 연결을 반환한다.

---

### DELETE /api/health-connections/{connectionId}

**Summary**: Samsung Health 연결 해제

204 No Content

상태를 disconnected로 변경한다. 이미 저장된 건강 기록과 동기화 이력은 삭제하지 않는다.

---

### POST /api/health-sync-runs

**Summary**: 모바일이 읽은 Samsung Health 데이터 배치 업로드

**Headers**: Idempotency-Key 필수

| 필드 | 타입 | 필수 | 제약 |
|---|---|:---:|---|
| connectionId | UUID | O | 본인 연결 |
| syncMode | string | O | initial, incremental, manual |
| cursorState | object | O | 데이터 유형별 커서 |
| records | object[] | O | 최대 500건 |

~~~json
{
  "connectionId": "b72f63be-b0f5-49b0-945a-24dedb3a4838",
  "syncMode": "incremental",
  "cursorState": {"steps": "2026-09-03T05:00:00Z"},
  "records": [
    {
      "metric": "activity",
      "externalRecordId": "samsung-activity-20260903",
      "sourceUpdatedAt": "2026-09-03T05:30:00Z",
      "recordedAt": "2026-09-03",
      "data": {
        "steps": 4620,
        "floors": 4,
        "activeTimeMinutes": 38,
        "distanceM": 3210,
        "activeCaloriesKcal": 220
      }
    }
  ]
}
~~~

202 Accepted는 syncRunId, status=running, receivedCount를 반환한다.

**업무 규칙**

- Samsung Health SDK 접근과 원천 데이터 읽기는 모바일 앱이 담당한다.
- 서버는 externalRecordId와 sourceUpdatedAt으로 신규·갱신·중복 제외를 판정한다.
- 직접 입력과 같은 측정시각·기록 판정 키가 겹치면 직접 입력값을 우선한다.
- 더 늦은 측정시각의 Samsung Health 값은 신규 기록이다.

**DB 매핑**: health_sync_runs, lifestyle_*  
**Errors**: 413 HEALTH-001, 422 HEALTH-002

---

### GET /api/health-sync-runs/{syncRunId}

**Summary**: 건강 데이터 동기화 결과 조회

~~~json
{
  "success": true,
  "data": {
    "syncRunId": "c5c808fc-222f-4c3d-8680-2f74152be50c",
    "status": "completed",
    "syncMode": "incremental",
    "requestedDataTypes": ["activity"],
    "receivedCount": 1,
    "insertedCount": 1,
    "updatedCount": 0,
    "skippedCount": 0,
    "cursorState": {"steps": "2026-09-03T05:30:00Z"},
    "completedAt": "2026-09-03T05:31:12Z"
  },
  "message": "동기화 실행을 조회했습니다.",
  "meta": null
}
~~~

## Tag: Health

### GET /api/health/summary

**Summary**: 내 건강 통합 리포트 조회

| 쿼리 | 타입 | 필수 | 기본값 | 설명 |
|---|---|:---:|---|---|
| period | string | - | 7d | 7d, 30d, 90d, 1y |
| baseDate | date | - | 오늘 | Asia/Seoul 기준 종료일 |

~~~json
{
  "success": true,
  "data": {
    "period": {"code": "7d", "from": "2026-08-28", "to": "2026-09-03", "timezone": "Asia/Seoul"},
    "integratedInsight": {
      "title": "수면은 회복 중이고 활동량은 조금 줄었어요.",
      "description": "최근 7일의 생활 건강과 최근 검진을 함께 분석했습니다.",
      "dataSufficient": true
    },
    "metrics": [
      {
        "metric": "sleep",
        "latestValue": 432,
        "unit": "minute",
        "averageValue": 424,
        "changeValue": 32,
        "trend": "increased",
        "validDays": 6,
        "requiredDays": 5,
        "dataSufficient": true,
        "emptyStateAction": null
      }
    ],
    "latestCheckup": {
      "recordId": "4076edb6-c9bc-403b-b951-d287f78c609d",
      "checkupDate": "2026-07-13",
      "cautionCount": 3
    }
  },
  "message": "내 건강 요약을 조회했습니다.",
  "meta": null
}
~~~

**DB 매핑**: health_metric_policies, health_checkup_records/results, lifestyle_*

---

### GET /api/health/{metric}

**Summary**: 기간별 건강 지표 원천값·집계 조회

metric 허용값은 sleep, activity, exercise, bio, nutrition, water다.

| 쿼리 | 타입 | 필수 | 설명 |
|---|---|:---:|---|
| period | string | - | 7d, 30d, 90d, 1y |
| baseDate | date | - | 기본 오늘, 한국 날짜 |
| aggregation | string | - | raw, day, week, month |
| bioType | string | - | metric=bio일 때 |
| cursor | string | - | raw 조회 |
| limit | integer | - | raw 조회 |

**Errors**: 400 HEALTH-003, 422 HEALTH-004

---

### POST /api/health/{metric}/records

**Summary**: 생활 건강 기록 직접 입력

**Headers**: Idempotency-Key 필수

혈압 예시:

~~~json
{
  "measuredAt": "2026-09-03T06:00:00Z",
  "bioType": "blood_pressure",
  "systolicMmhg": 120,
  "diastolicMmhg": 78,
  "pulseBpm": 68
}
~~~

물 섭취 예시:

~~~json
{
  "consumedAt": "2026-09-03T06:00:00Z",
  "amountMl": 250
}
~~~

201 Created는 기록 ID, metric, recordedAt, source=manual, isUserOverride를 반환한다. 과거 기록 수정·삭제 API는 v1에서 제공하지 않는다.

---

# 7부. 홈·브리핑·이상 신호 API

## Tag: Home

### GET /api/home

**Summary**: 홈 카드 전체 조합 조회

~~~json
{
  "success": true,
  "data": {
    "date": "2026-09-03",
    "alerts": [],
    "modules": [
      {
        "moduleCode": "daily_briefing",
        "visible": true,
        "displayOrder": 1,
        "state": "ready",
        "content": {
          "briefingId": "9acaac3d-3b43-4235-a46b-a3398a34e14f",
          "headline": "수면은 안정적이고 걸음 수가 늘었어요."
        }
      },
      {
        "moduleCode": "key_metrics",
        "visible": true,
        "displayOrder": 2,
        "state": "empty",
        "content": null,
        "emptyStateAction": "connect_samsung_health"
      }
    ]
  },
  "message": "홈 화면을 조회했습니다.",
  "meta": null
}
~~~

- 기본 모듈은 daily_briefing, key_metrics, medication, missions다.
- 모든 모듈은 숨길 수 있다.
- 데이터 부족 카드는 empty 상태와 다음 행동을 반환한다.
- 미확인 이상 신호는 모듈보다 먼저 반환한다.
- 오늘 브리핑이 없으면 이 조회가 브리핑 생성을 한 번만 비동기로 시작한다.
- 생성이 시작된 경우 HTTP 응답은 `200 OK`를 유지하고 `daily_briefing.state=generating`, `content=null`, `pollAfterMs=1500`을 반환한다. 앱은 같은 `GET /api/home`을 다시 호출한다.
- 이미 생성 중이거나 완료된 브리핑이 있으면 중복 생성하지 않는다.

---

### GET /api/users/me/home-modules

**Summary**: 홈 카드 표시·순서 조회

**DB 매핑**: user_home_modules

---

### PUT /api/users/me/home-modules

**Summary**: 홈 카드 설정 전체 교체

~~~json
{
  "modules": [
    {"moduleCode": "daily_briefing", "visible": true, "displayOrder": 1},
    {"moduleCode": "key_metrics", "visible": true, "displayOrder": 2},
    {"moduleCode": "medication", "visible": false, "displayOrder": 3},
    {"moduleCode": "missions", "visible": true, "displayOrder": 4}
  ]
}
~~~

200 OK는 정규화된 전체 배열을 반환한다. 모듈 코드·순서 중복은 422 HOME-001이다.

---

### GET /api/health/briefings/latest

**Summary**: 최근 브리핑 조회

status는 pending, generated, data_insufficient, failed다. 생성 완료 시 headline, body, sections, generatedAt과 화면에 필요한 최소 근거만 반환한다. 공개 API에는 별도 생성 요청 경로를 두지 않으며 생성 시작은 `GET /api/home`의 지연 생성 규칙으로 통일한다.

---

### GET /api/health-alerts

**Summary**: 건강 이상 신호 조회

**Query**: status(기본 active), cursor, limit

**DB 매핑**: health_alerts

---

### POST /api/health-alerts/{alertId}/acknowledge

**Summary**: 이상 신호 확인 처리

**Headers**: Idempotency-Key 필수

200 OK는 alertId, status=acknowledged, acknowledgedAt을 반환한다.

---

# 8부. 복약·기기·알림 API

## Tag: Medications

### GET /api/users/medications

**Summary**: 약 목록 조회

**Query**: status(기본 active), cursor, limit

종료·보관 약은 기본 목록에서 제외한다. 항목에는 medicationId, displayName, dosageText, instructions, startDate, endDate, status, schedules를 포함한다.

---

### POST /api/users/medications

**Summary**: 약과 하루 복용 일정 직접 등록

**Headers**: Idempotency-Key 필수

~~~json
{
  "displayName": "혈압약",
  "doseAmount": 1,
  "doseUnit": "정",
  "dosageText": "1회 1정",
  "instructions": "아침 식후",
  "startDate": "2026-09-03",
  "endDate": "2026-09-30",
  "scheduledTimes": ["08:00:00", "20:00:00"]
}
~~~

201 Created는 medicationId, 약 정보, schedules를 반환한다.

**트랜잭션**: 약·일정·기간 내 복용 회차를 함께 생성하거나 회차 생성 예약을 등록한다.

---

### GET /api/users/medications/{medicationId}

**Summary**: 약 상세·일정·최근 복용 이력 조회

**DB 매핑**: user_medications, medication_schedules, medication_intakes

---

### PATCH /api/users/medications/{medicationId}

**Summary**: 약 정보와 향후 복용 일정 수정

POST /medications 필드의 부분 업데이트를 허용한다.

- 처리 완료된 과거 복용 이력과 스냅샷은 변경하지 않는다.
- pending 미래 회차만 새 일정 기준으로 재생성한다.
- 종료일이 지나면 status를 completed로 전환한다.

---

### DELETE /api/users/medications/{medicationId}

**Summary**: 복약 종료 처리

204 No Content

물리 삭제하지 않고 `status=archived`, `endedAt`을 기록한다. 아직 처리되지 않은 미래 복용 회차는 취소하고 과거 복용 이력은 유지한다.

---

### POST /api/users/medications/ocr-jobs

**Summary**: 약봉투·처방전 OCR 작업 생성

**Content-Type**: multipart/form-data  
**Headers**: Idempotency-Key 필수

파일 제약과 비동기 상태는 건강검진 OCR과 같으며 documentType은 medication이다.

---

### GET /api/users/medications/ocr-jobs/{jobId}

**Summary**: 복약 OCR 상태·인식 결과 조회

완료 응답 items에는 itemOrder, rawName, rawDosage, normalizedName, normalizedDosage, confidence를 포함한다.

---

### POST /api/users/medications/ocr-jobs/{jobId}/confirm

**Summary**: 확인한 약과 복용 일정 생성

**Headers**: Idempotency-Key 필수

~~~json
{
  "medications": [
    {
      "itemOrder": 1,
      "displayName": "혈압약",
      "doseAmount": 1,
      "doseUnit": "정",
      "dosageText": "1회 1정",
      "instructions": "식후",
      "startDate": "2026-09-03",
      "endDate": "2026-09-30",
      "scheduledTimes": ["08:00:00"]
    }
  ]
}
~~~

약·일정·OCR 확정 결과를 한 트랜잭션으로 생성한다. 같은 작업 재확정은 409 OCR-004다.

---

### GET /api/users/medication-intakes

**Summary**: 기간별 복약 예정·완료 이력 조회

| 쿼리 | 타입 | 필수 | 설명 |
|---|---|:---:|---|
| from | date | O | 한국 날짜 |
| to | date | O | 한국 날짜 |
| status | string | - | pending, taken, skipped, missed |
| cursor | string | - | 없음 |
| limit | integer | - | 기본 50, 최대 100 |

---

### POST /api/users/medication-intakes/{intakeId}/complete

**Summary**: 복약 완료 처리

**Headers**: Idempotency-Key 필수

~~~json
{"actionSource": "app"}
~~~

actionSource는 app 또는 push다. 200 OK는 status=taken, actedAt을 반환한다.

---

### POST /api/users/medication-intakes/{intakeId}/skip

**Summary**: 복약 건너뜀 처리

**Headers**: Idempotency-Key 필수

~~~json
{
  "actionSource": "app",
  "reason": "오늘은 복용하지 않음"
}
~~~

taken 또는 skipped로 확정된 회차는 v1에서 다시 변경하지 않는다.

## Tag: Notifications

### POST /api/devices/push-tokens

**Summary**: 기기 푸시 토큰 등록·갱신

**Headers**: Idempotency-Key 필수

~~~json
{
  "deviceIdentifier": "앱-설치-단위-식별자",
  "platform": "android",
  "pushToken": "fcm-token"
}
~~~

서버는 deviceIdentifier의 해시만 저장한다.

**DB 매핑**: private.device_tokens

---

### DELETE /api/devices/push-tokens/{deviceTokenId}

**Summary**: 현재 기기 푸시 토큰 비활성화

204 No Content

로그아웃 전에 호출하며 is_active=false와 invalidated_at을 기록한다.

---

### GET /api/notifications

**Summary**: 알림 이력 조회

**Query**: status, notificationType, cursor, limit

~~~json
{
  "success": true,
  "data": {
    "items": [
      {
        "notificationId": "c8d625d5-d5ab-49cc-8307-d56a64d67793",
        "notificationType": "medication_reminder",
        "title": "복약 시간이에요",
        "body": "혈압약을 복용할 시간입니다.",
        "status": "sent",
        "targetType": "medication_intake",
        "targetId": "5eb3e5b8-acde-4e40-acde-78f9b7f054ad",
        "scheduledAt": "2026-09-03T07:00:00Z",
        "sentAt": "2026-09-03T07:00:01Z",
        "openedAt": null
      }
    ]
  },
  "message": "알림 목록을 조회했습니다.",
  "meta": {
    "nextCursor": null,
    "hasNext": false,
    "unreadCount": 3
  }
}
~~~

`unreadCount`는 현재 필터와 무관한 사용자의 전체 미열람 알림 수다.

---

### POST /api/notifications/{notificationId}/open

**Summary**: 알림 열람 및 딥링크 조회

**Headers**: Idempotency-Key 필수

~~~json
{
  "success": true,
  "data": {
    "notificationId": "c8d625d5-d5ab-49cc-8307-d56a64d67793",
    "status": "opened",
    "openedAt": "2026-09-03T07:00:00Z",
    "deepLink": "heapy://medications/intakes/5eb3e..."
  },
  "message": "알림을 확인했습니다.",
  "meta": null
}
~~~

복약 완료는 잠금 해제 후 complete API를 호출한다. 수분·미션 알림은 화면 이동만 제공한다. 알림 금지 시간과 상세 수신 설정은 v1 범위 밖이다.

---

# 9부. 미션·코인·코디 API

## Tag: Missions

### GET /api/missions/today

**Summary**: 오늘 미션·코인 잔액·현재 코디 요약 조회

~~~json
{
  "success": true,
  "data": {
    "date": "2026-09-03",
    "missions": [
      {
        "userMissionId": "e01f1002-0462-4846-ae39-f1d46c709145",
        "missionCode": "walk_6000",
        "title": "6,000보 걷기",
        "description": "오늘 6,000보를 걸어보세요.",
        "source": "system",
        "targetValue": 6000,
        "progressValue": 4200,
        "progressRate": 70,
        "status": "active",
        "startsAt": "2026-09-02T15:00:00Z",
        "endsAt": "2026-09-03T14:59:59Z"
      }
    ],
    "coin": {"balance": 240},
    "outfit": {
      "companionCode": "heapy_cat",
      "slots": [
        {"slot": "head", "inventoryId": "9403a03a-6388-4853-b8da-5d34c978f963", "assetUrl": "https://..."}
      ]
    }
  },
  "message": "오늘의 미션 화면을 조회했습니다.",
  "meta": null
}
~~~

한국 날짜 기준 미션은 최대 10개다. `coin.balance`는 불변 코인 원장을 합산한 값이며 `outfit.slots`는 현재 착용 상태다.

---

### GET /api/missions/{userMissionId}/progress

**Summary**: 단일 미션 진행률 조회

~~~json
{
  "success": true,
  "data": {
    "userMissionId": "e01f1002-0462-4846-ae39-f1d46c709145",
    "targetValue": 6000,
    "progressValue": 4200,
    "progressRate": 70,
    "status": "active",
    "lastCalculatedAt": "2026-09-03T08:10:00Z"
  },
  "message": "미션 진행률을 조회했습니다.",
  "meta": null
}
~~~

---

### POST /api/missions/{userMissionId}/accept

**Summary**: 제안 미션 수락

**Headers**: Idempotency-Key 필수

제안 상태만 active로 변경한다. 하루 10개 제한 초과는 409 MISSION-002다.

---

### POST /api/missions/{userMissionId}/complete

**Summary**: 미션 완료·피드백·코인 지급

**Headers**: Idempotency-Key 필수

~~~json
{"feedback": {"reason": "목표를 달성했어요"}}
~~~

~~~json
{
  "success": true,
  "data": {
    "userMissionId": "e01f1002-0462-4846-ae39-f1d46c709145",
    "status": "completed",
    "completedAt": "2026-09-03T08:20:00Z",
    "rewardCoin": 10,
    "coinBalance": 240
  },
  "message": "미션을 완료하고 코인을 지급했습니다.",
  "meta": null
}
~~~

**트랜잭션**: 소유권·상태·완료 조건 검증 → 미션 완료 → 피드백 생성 → 코인 원장 적립. 건강 데이터는 진행률만 자동 반영한다.

---

### POST /api/missions/{userMissionId}/abandon

**Summary**: 미션 포기

**Headers**: Idempotency-Key 필수

~~~json
{"reason": "현재 수행하기 어려움"}
~~~

포기한 같은 missionCode는 다음 한국 날짜 2일 동안 추천하지 않는다.

---

### GET /api/missions/history

**Summary**: 미션 달력·이력 조회

**Query**: from, to, status, cursor, limit

---

### GET /api/coins/balance

**Summary**: 불변 원장 기준 코인 잔액 조회

~~~json
{
  "success": true,
  "data": {
    "balance": 240,
    "totalEarned": 320,
    "totalSpent": 80,
    "calculatedAt": "2026-09-03T08:21:00Z"
  },
  "message": "코인 잔액을 조회했습니다.",
  "meta": null
}
~~~

---

### GET /api/coins/ledger

**Summary**: 코인 거래 이력 조회

**Query**: entryType, cursor, limit

원장 행은 수정·삭제하지 않는다.

---

### GET /api/shop/items

**Summary**: 판매 중인 코디 아이템 조회

**Query**: slot, cursor, limit

itemId, itemCode, slot, name, currentPrice, saleStatus, owned를 반환한다.

---

### POST /api/shop/purchases

**Summary**: 코인으로 아이템 구매

**Headers**: Idempotency-Key 필수

~~~json
{"itemId": "53aefb21-2409-46fc-b536-eb42daf1c53b"}
~~~

~~~json
{
  "success": true,
  "data": {
    "purchaseId": "3c9c6816-ef69-43fc-83cc-19e38040e989",
    "itemId": "53aefb21-2409-46fc-b536-eb42daf1c53b",
    "chargedCoin": 80,
    "coinBalance": 160,
    "status": "completed",
    "cancellableUntil": "2026-09-10T08:30:00Z"
  },
  "message": "아이템을 구매했습니다.",
  "meta": null
}
~~~

현재 가격 확인, 잔액 잠금·검증, 차감 원장, 구매, 인벤토리 지급을 한 트랜잭션으로 처리한다. 이미 보유한 아이템은 재구매할 수 없다.

같은 사용자·요청 본문·`Idempotency-Key`로 재시도하면 새 결제를 만들지 않고 최초 성공 당시의 `purchaseId`, `chargedCoin`, `coinBalance`, `status`, `cancellableUntil`을 그대로 반환한다. 같은 키에 다른 본문을 사용하면 409 `IDEMPOTENCY_KEY_REUSED`다.

---

### POST /api/shop/purchases/{purchaseId}/cancel

**Summary**: 구매 후 7일 내 아이템 회수·환불

**Headers**: Idempotency-Key 필수

~~~json
{"reason": "단순 변심"}
~~~

착용 해제, 인벤토리 회수, 구매 취소, 실제 차감액 기준 환불 원장 생성을 한 트랜잭션으로 처리한다.

---

### GET /api/users/me/inventory

**Summary**: 보유 코디 아이템 조회

**Query**: slot, status, cursor, limit

---

### PUT /api/users/me/outfit

**Summary**: 슬롯별 착용 상태 전체 교체

~~~json
{
  "slots": [
    {"slot": "head", "inventoryId": "9403a03a-6388-4853-b8da-5d34c978f963"},
    {"slot": "body", "inventoryId": null}
  ]
}
~~~

본인이 보유한 owned 아이템만 착용할 수 있다. 슬롯 불일치·회수 아이템은 422 SHOP-004다.

---

# 10부. 히피 상담 API

## Tag: Chat

### GET /api/chat/sessions

**Summary**: 상담 세션 목록 조회

**Query**: cursor, limit

정렬은 lastMessageAt DESC NULLS LAST, createdAt DESC다.

---

### POST /api/chat/sessions

**Summary**: 상담 세션 생성

**Headers**: Idempotency-Key 필수

~~~json
{"companionCode": "heapy_cat"}
~~~

201 Created는 sessionId, title=새 대화, companionCode, createdAt을 반환한다.

---

### GET /api/chat/sessions/{sessionId}

**Summary**: 상담 세션 상세 조회

누적 summary와 내부 모델·프롬프트 정보는 공개 응답에서 제외한다.

---

### PATCH /api/chat/sessions/{sessionId}

**Summary**: 세션 제목 또는 상담 파트너 변경

~~~json
{
  "title": "혈압 상담",
  "companionCode": "heapy_dog"
}
~~~

파트너 변경 후에도 기존 문맥을 유지하며 이후 AI 메시지에 당시 파트너를 스냅샷으로 저장한다.

---

### DELETE /api/chat/sessions/{sessionId}

**Summary**: 상담 세션 삭제

204 No Content

세션, 메시지, 요약, 출처, 미승인 제안 행동을 함께 삭제한다. 이미 승인돼 생성된 사용자 미션은 유지한다.

---

### GET /api/chat/sessions/{sessionId}/messages

**Summary**: 상담 메시지·출처·제안 행동 조회

**Query**: cursor, limit

각 AI 메시지에 citations와 suggestedActions를 포함한다. 출처는 화면에서 기본 접힘 상태다.

---

### POST /api/chat/sessions/{sessionId}/stream

**Summary**: 사용자 질문 처리 및 AI 답변 SSE

**Headers**: Idempotency-Key 필수  
**Content-Type**: application/json  
**Response Content-Type**: text/event-stream

~~~json
{"message": "최근 혈압 결과가 어떤 의미인지 알려줘"}
~~~

| 이벤트 | 주요 필드 | 설명 |
|---|---|---|
| status | stage, message | 처리 단계 |
| delta | content | 답변 증분 |
| suggestedAction | action | 승인 전 제안 행동 |
| done | sessionId, userMessageId, assistantMessageId, responseStatus, citations, metadata | 저장 완료 및 전체 출처 |
| error | code, message, traceId | 실패 |

~~~text
event: status
data: {"stage":"retrieving","message":"관련 건강 정보를 확인하고 있어요."}

event: delta
data: {"content":"최근 기록을 기준으로 보면 "}

event: suggestedAction
data: {"action":{"suggestedActionId":"f680...","title":"7일간 혈압 기록하기","expiresAt":"2026-09-10T08:00:00Z"}}

event: done
data: {"sessionId":"2c0e...","userMessageId":"8ea1...","assistantMessageId":"93f7...","responseStatus":"completed","citations":[{"displayOrder":1,"sourceTitle":"대한고혈압학회","sourceUrl":"https://...","evidenceSnippet":"혈압 관리 근거 요약"}],"metadata":{"intent":"comprehensive","grounded":true}}
~~~

- 첫 질문은 빈 문맥, 이후에는 최근 메시지 20개와 누적 요약을 사용한다.
- 개인 검진 데이터는 comprehensive 경로에서만 인증·소유권 검증 후 사용한다.
- 긴급 상황은 긴급 행동 안내를 먼저 배치하되 요청 정보도 제공한다.
- 정상 완료 시 질문과 답변을 저장하고 `done` 이벤트에 정렬된 전체 `citations`를 한 번에 포함한다. 출처별 이벤트는 사용하지 않는다.
- 연결 중단 또는 생성 실패 시점까지 답변 텍스트가 하나 이상 생성됐다면 질문과 부분 답변을 저장하고 AI 메시지의 `responseStatus=partial`로 표시한다. 부분 답변에는 출처와 제안 행동을 확정·저장하지 않는다.
- 텍스트가 한 글자도 생성되지 않은 생성 실패는 질문과 빈 답변을 저장하지 않는다. DB 저장 자체가 실패한 경우에도 해당 턴을 완료로 간주하지 않는다.
- 메시지 목록 조회 시 `responseStatus=partial`과 사용자용 실패 안내 문구를 함께 반환하여 앱이 생성된 부분까지 보여주고 재질문을 안내할 수 있게 한다.
- v1은 Last-Event-ID 기반 스트림 재개를 지원하지 않는다.

**DB 정합성**: 현재 DB 설계의 `chat_messages.response_status` 열을 그대로 사용한다. CHECK 제약을 추가할 때는 최소 `completed`, `partial`을 허용해야 하며 사용자 역할 메시지는 `completed`를 사용한다.

---

### POST /api/chat/suggested-actions/{actionId}/accept

**Summary**: AI 제안 승인 및 미션 생성

**Headers**: Idempotency-Key 필수

제안 소유권·pending·만료와 오늘 미션 10개 제한을 확인한 뒤 제안 승인과 미션 생성을 한 트랜잭션으로 처리한다.

~~~json
{
  "success": true,
  "data": {
    "suggestedActionId": "f68031e9-f3e4-43bd-a0a9-8aa58a7bcdaa",
    "status": "accepted",
    "mission": {
      "userMissionId": "e01f1002-0462-4846-ae39-f1d46c709145",
      "missionCode": "record_bp_7days",
      "title": "7일간 혈압 기록하기",
      "status": "active",
      "targetValue": 7,
      "progressValue": 0,
      "startsAt": "2026-09-03T08:20:00Z",
      "endsAt": "2026-09-10T14:59:59Z"
    }
  },
  "message": "제안 행동을 수락하고 미션을 생성했습니다.",
  "meta": null
}
~~~

---

### POST /api/chat/suggested-actions/{actionId}/reject

**Summary**: AI 제안 거절

**Headers**: Idempotency-Key 필수

선택 본문 reason을 받을 수 있으며 미션은 생성하지 않는다.

---

# 11부. Spring Boot ↔ FastAPI 내부 AI API

## 11.1 내부 공통 계약

- Base URL은 /internal이다.
- 외부 게이트웨이에서 차단하고 사설 네트워크에서만 접근한다.
- Authorization: Bearer {internal_service_token}과 X-Request-Id를 사용한다.
- 사용자 Access Token과 개인 건강 원문을 FastAPI 로그에 저장하지 않는다.
- 내부 오류 원문은 외부 API 응답으로 그대로 전달하지 않는다.

## POST /internal/ocr

**Summary**: 문서 유형별 OCR·의료용어 정규화

**요청**: multipart/form-data의 file, documentType, inputType, jobId

**응답**: pageCount와 임시 OCR result

FastAPI는 검진·복약 업무 테이블에 확정 데이터를 직접 저장하지 않는다.

## POST /internal/briefings/generate

**Summary**: 최소 건강 집계로 하루 브리핑 생성

**요청 주요 필드**: userContext, metricPolicyVersions, briefingDate, promptVersion

**응답 주요 필드**: status, headline, body, sections, evidenceSummary, modelName, modelVersion

개인 원천 기록 전체가 아니라 생성에 필요한 최소 집계만 전달한다.

## POST /internal/chat/stream

**Summary**: 문맥 판단·의료용어 정규화·Intent·Safety Guard·RAG·답변 스트리밍

**요청 주요 필드**: sessionId, rawQuery, recentMessages, summary, companionCode, userContext

**응답**: status, delta, suggestedAction, done, error 내부 SSE

Spring Boot가 외부 SSE 계약으로 변환·중계하고 완료 시 DB 트랜잭션을 수행한다.

---

# 12부. 오류 코드

## 12.1 공통·인증

| HTTP | 코드 | 의미 |
|---:|---|---|
| 400 | COMMON-001 | 입력값 검증 실패 |
| 400 | COMMON-002 | 지원하지 않는 정렬·필터 |
| 401 | AUTH-001 | 이메일 또는 비밀번호 불일치 |
| 403 | AUTH-002 | 이메일 인증 미완료 |
| 401 | AUTH-003 | Access Token 없음·만료·서명 검증 실패 |
| 401 | AUTH-004 | Refresh Token 없음·만료·재사용 |
| 409 | AUTH-005 | 이미 가입된 이메일 |
| 422 | AUTH-006 | 비밀번호 정책 불충족 |
| 410 | AUTH-007 | 비밀번호 재설정 토큰 만료·사용 완료 |
| 429 | AUTH-008 | 인증 요청 제한 초과 |
| 503 | AUTH-009 | Supabase Auth 연결 장애 |
| 404 | RESOURCE_NOT_FOUND | 없거나 소유하지 않은 리소스 |
| 409 | IDEMPOTENCY_KEY_REUSED | 같은 키에 다른 본문 사용 |
| 429 | COMMON-429 | 요청 제한 초과 |
| 500 | COMMON-500 | 서버 내부 오류 |

## 12.2 도메인

| HTTP | 코드 | 의미 |
|---:|---|---|
| 409 | ONBOARDING-001 | 필수 프로필·약관 미완료 |
| 422 | ONBOARDING-002 | 온보딩 완료 필수값 누락 |
| 409 | TERMS-002 | 필수 약관 철회 불가 |
| 413 | OCR-001 | OCR 파일·페이지 제한 초과 |
| 415 | OCR-002 | 지원하지 않는 OCR 형식 |
| 410 | OCR-003 | OCR 작업 만료 |
| 409 | OCR-004 | 이미 확정된 OCR 작업 |
| 422 | OCR-005 | OCR 확정 결과 검증 실패 |
| 422 | CHECKUP-002 | 비교 가능한 검진 회차 부족 |
| 413 | HEALTH-001 | 동기화 배치 제한 초과 |
| 422 | HEALTH-002 | 동기화 레코드 검증 실패 |
| 400 | HEALTH-003 | 지원하지 않는 건강 지표 |
| 422 | HEALTH-004 | 기간·집계 조건 오류 |
| 422 | HOME-001 | 홈 모듈 구성 오류 |
| 409 | MEDICATION-001 | 이미 처리된 복약 회차 |
| 409 | MISSION-001 | 미션 상태 충돌 |
| 409 | MISSION-002 | 하루 미션 10개 초과 |
| 422 | MISSION-003 | 미션 완료 조건 미충족 |
| 409 | COIN-001 | 코인 잔액 부족 |
| 409 | SHOP-001 | 판매 중이 아닌 아이템 |
| 409 | SHOP-002 | 이미 보유한 아이템 |
| 409 | SHOP-003 | 취소 기간 초과·이미 취소 |
| 422 | SHOP-004 | 착용할 수 없는 아이템 |
| 409 | CHAT-001 | 상담 세션 상태 충돌 |
| 503 | CHAT-002 | 상담 AI 서비스 장애 |
| 410 | CHAT-003 | AI 제안 행동 만료 |
| 503 | AI-001 | 내부 AI 서비스 장애 |

---

# 13부. 트랜잭션·멱등성·보존

## 13.1 트랜잭션 경계

| 유스케이스 | 단일 트랜잭션 작업 | 중복 방지 |
|---|---|---|
| 검진 OCR 확정 | 검진 회차·결과·교정 로그·작업 확정 | jobId, Idempotency-Key |
| 복약 등록 | 약·일정·향후 복용 회차 | Idempotency-Key |
| 복약 완료·건너뜀 | 복용 상태·알림 행동 | intakeId, Idempotency-Key |
| 미션 완료 | 상태·피드백·코인 지급 | userMissionId, Idempotency-Key |
| 아이템 구매 | 코인 차감·구매·인벤토리 | Idempotency-Key |
| 구매 취소 | 착용 해제·회수·취소·환불 | purchaseId, Idempotency-Key |
| AI 행동 승인 | 제안 승인·미션 생성 | actionId, Idempotency-Key |
| 상담 정상 완료 | 메시지 2건·요약·출처·제안 | 세션별 요청 키 |
| 상담 부분 저장 | 질문·생성된 답변·partial 상태, 출처·제안 제외 | 세션별 요청 키 |

## 13.2 보존·삭제

| 데이터 | v1 정책 |
|---|---|
| OCR 원본·변환 이미지 | 처리 종료 직후 삭제 |
| 건강검진 전체 OCR 결과 | 확정·이탈·만료 시 임시 저장소에서 삭제 |
| 건강검진 교정 로그 | 실제 수정 항목만 보관 |
| 복약 OCR 인식값·확정값 | 복약 기록과 함께 보관 |
| 과거 건강 기록 | 보관, 수정·삭제 API 없음 |
| 종료·보관 약 | 기본 목록에서 숨김, 이력 유지 |
| 확인·정상화 이상 신호 | 7일 후 삭제 |
| 알림 운영 이력 | 30일 보관 |
| 상담 세션 | 사용자가 삭제할 때까지 보관 |
| 중단된 상담 부분 답변 | responseStatus=partial로 세션과 함께 보관 |
| 코인 원장 | 수정·삭제 금지, 반대 거래로 정정 |

---

# 14부. API-DB 매핑 요약

| API 영역 | 읽기 | 쓰기 |
|---|---|---|
| Authentication | Supabase auth.users, users, terms, user_terms_consents | Supabase Auth 세션·자격 증명 |
| Me·Terms | users, terms, user_terms_consents | users, user_terms_consents |
| Checkups | ocr_jobs, health_checkup_records/results, master_checkup_item | ocr_jobs, ocr_correction_logs, health_checkup_records/results |
| Health Connections | health_data_connections, health_sync_runs | 동일 |
| Health | lifestyle_activity/exercise/bio/nutrition/water_intake/sleep | 동일 |
| Home | user_home_modules, daily_health_briefings, health_alerts | 동일 |
| Medications | user_medications, medication_schedules/intakes, medication_ocr_results | 동일 |
| Notifications | private.device_tokens, notifications | 동일 |
| Missions | user_missions, mission_feedback, coin_ledger | 동일 |
| Shop·Outfit | shop_items, shop_purchases, user_inventory, equipped_items | 구매 관련 테이블 |
| Chat | chat_sessions/messages/citations/suggested_actions | 동일 |

---

# 15부. v1 범위 제외

- 소셜 로그인
- 관리자 API
- 회원 탈퇴·계정 데이터 삭제 API: 법적 보존·삭제 정책 확정 후 별도 명세
- 알림 금지 시간과 상세 수신 설정
- 캐릭터 컨디션 4단계와 미수행 독촉 알림
- 음식·영양소·혈당 기반 미션 활성화
- 과거 건강 기록 수정·삭제
- 중단된 상담 SSE 재개
- 모바일 앱의 Supabase Data API 직접 업무 테이블 쓰기
- rag_chunks 신규 사용: v1은 Pinecone 유지

---

# 16부. 구현·검증 체크리스트

- OpenAPI 3.0 문서와 이 Markdown의 경로·DTO·오류 코드를 동일하게 유지한다.
- JWT subject와 리소스 소유권을 함께 검증한다.
- 다른 사용자 리소스 요청이 모두 404인지 확인한다.
- RLS와 GRANT를 별도로 테스트한다.
- 멱등성 재전송과 같은 키·다른 본문 충돌을 테스트한다.
- OCR 원본·변환 이미지가 처리 후 삭제되는지 확인한다.
- Asia/Seoul 날짜 경계 00:00을 테스트한다.
- 검진 확정, 복약 완료, 미션 완료, 구매·취소, AI 행동 승인 트랜잭션을 테스트한다.
- SSE `done`이 DB 저장 성공 후에만 전달되는지 확인한다.
- 상담 중단 시 생성된 텍스트가 있으면 partial 메시지만 저장되고 출처·제안 행동은 저장되지 않는지 확인한다.
- 로그·오류에 토큰·건강 원문·내부 예외가 없는지 확인한다.

---

# 17부. 참조 문서

- Reference/HEAPY_전체_시스템_아키텍처_v1.md
- Reference/HEAPY_BACKEND_API_검토확정결과.md
- Reference/Notion_산출물/HEAPY_v1_DB_ERD/HEAPY_DB_개발기준서_v1.md
- Reference/Notion_산출물/HEAPY_v1_DB_ERD/02_HEAPY_v1_물리적_DB_설계_PostgreSQL.md
- Reference/HEAPY_DB_전체설계_v1.md
- Reference/HEAPY_DB_물리설계_v1.md
- Reference/HEAPY_DB_ERD_v1.md
- Reference/HEAPY_화면검토_확정결과.md
- Reference/HEAPY_화면_기능_데이터_매핑표.md
- docs/api_spec.md
- docs/README.md
- docs/pipeline_state_design.md
- docs/node_io_spec.md
- docs/supabase_auth_chat_db_design.md
- docs/medical_term_db_design.md

DB 컬럼·관계·보존 정책이 충돌하면 HEAPY_DB_개발기준서_v1.md를 우선한다. 기존 docs/api_spec.md는 FastAPI 구현 참고 문서이며 모바일에 노출되는 최종 공개 계약은 이 문서를 기준으로 한다.

---

# 18부. 변경 이력

| 버전 | 날짜 | 작성자 | 변경 내용 |
|---|---|---|---|
| v1.1 | 2026-09-03 | 김진우 | 46개 검토 답변 반영: `/v1` 경로 제거, Spring Boot 인증 파사드, 통합 화면 응답, 지연 브리핑, 부분 상담 저장, 고정 SSE 이벤트 확정 |
| v1.0 | 2026-09-03 | 김진우 | 확정 ERD·화면 요구사항과 권장 검토안을 반영한 공개·내부 API 상세 명세 작성 |
