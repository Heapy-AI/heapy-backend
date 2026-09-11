from pathlib import Path
import re, json

root=Path('C:/Users/jinwo/heapy-backend')
stage=root/'.tmp/api-spec'
path=root/'docs/api/HEAPY_BACKEND_API_명세_v1.md'
original=path.read_text(encoding='utf-8')
reference=Path('C:/Users/jinwo/heapy-ai-health/Reference/HEAPY_BACKEND_API_명세_v1.md')
(stage/'백엔드_원본.md').write_text(original,encoding='utf-8')
(stage/'Reference_원본.md').write_text(reference.read_text(encoding='utf-8'),encoding='utf-8')
amendments=(stage/'구현계약.md').read_text(encoding='utf-8')
amendments=amendments.replace('진행 중 요청 등 충돌 상태는 `409 CHAT-002`로 거부한다.', '삭제 후 해당 세션은 조회할 수 없다.')
pattern=r'^### ((?:GET|POST|PATCH|PUT|DELETE) /\S+)\n.*?(?=^#{1,3} |\Z)'
def sections(value):
    return {m.group(1):m.group(0).strip() for m in re.finditer(pattern,value,re.M|re.S)}
updates=sections(amendments)
old=sections(original)
text=original
for key,body in updates.items():
    if key in old:
        text=re.sub(r'^### '+re.escape(key)+r'\n.*?(?=^#{1,3} |\Z)',lambda m:body+'\n\n---\n\n',text,flags=re.M|re.S)
new='\n\n---\n\n'.join(v for k,v in updates.items() if k not in old)
target=re.search(r'^# 7부\.',text,re.M)
assert target
text=text[:target.start()]+new+'\n\n---\n\n'+text[target.start():]
text=text.replace('> 문서 버전: v1.1', '> 최종 대조일: 2026-09-11  \n> 문서 버전: v1.2')
text=text.replace('> 상태: v1 구현 기준 확정본', '> 상태: 구현 계약 대조 완료 · 미구현 API 설계 보존')
intro='''
## 문서 적용 범위와 구현 상태

2026-09-11 백엔드 `feature/jinu`의 컨트롤러·DTO·서비스·검증 코드를 대조했다. 이 문서는 **구현된 API의 현재 계약과 아직 구현되지 않은 API의 설계도를 함께 보관한다.** API 목록에 있다는 이유만으로 호출 가능한 상태로 간주하지 않는다. 구현 여부는 배포·기능 플래그 활성화와도 구별한다.

| 영역 | 현재 구현 범위 | 그대로 남긴 미구현 설계의 예 |
| --- | --- | --- |
| 인증 | 회원가입·로그인·로그아웃 | 세션 조회·토큰 갱신·인증 메일 재발송·비밀번호 재설정 |
| 사용자·약관 | 프로필 조회·수정, 전체 프로필 온보딩 완료, 약관 조회·동의 | 사용자 대시보드 |
| 검진 | OCR 생성·폴링·확정·임시 작업 종료, 회차 목록·상세·비교 | 기존 미구현 설계는 유지 |
| 생활 건강 | 연결 조회·등록, 동기화 저장·상태·결과, 요약·기간 조회·직접 입력, 물 수정·삭제, 당일 분석 조회 | 연결 해제 및 아직 구현하지 않은 작업 |
| 홈 | GET /api/home의 현재 조합 응답 | 홈 모듈 설정, 별도 브리핑·알림 API |
| 상담 | 기능 활성 시 세션 CRUD·메시지 조회·답변 SSE | 제안 행동 승인·거절 |
| 미션·복약·코인·코디·알림 | 이 문서 대조 시점의 공개 컨트롤러 미구현 | **경로·요청·응답·업무 규칙을 모두 기존 설계 그대로 유지** |

최종 변경은 미션·복약 기능 삭제나 범위 축소를 의미하지 않는다. 기존 Reference의 API 명세·전체 시스템 아키텍처·화면검토 확정결과·DB 물리설계를 대조했으며, 구현 차이는 각 API의 현재 계약과 근거로 명시한다. 이 문서 수정으로 서버 코드·DB·배포 설정은 변경하지 않는다.

'''
text=text.replace('# 1부. 시스템 아키텍처 및 DB 요약',intro+'# 1부. 시스템 아키텍처 및 DB 요약',1)
text=text.replace('          ├─ 건강검진·복약 OCR','          ├─ 내부 건강 분석',1)
text=text.replace('- 모바일 앱은 Spring Boot `/api/auth/**`만 호출하며 Spring Boot가 Supabase Auth에 이메일 인증 처리를 위임한다.', '- 인증 요청은 Spring Boot `/api/auth/**`를 거쳐 Supabase Auth로 위임한다. 다른 업무 요청은 해당 Spring `/api/**`를 사용한다. 구현된 건강검진 OCR은 Spring의 Lambda Invoke 어댑터를 사용하며, 아래 내부 OCR HTTP 경로 설계와 구별한다.',1)
text=text.replace('## 2.8 비동기 상태','## 2.8 비동기 상태\n\n아래 표는 미구현 API를 포함한 기본 설계다. 구현된 검진 OCR은 pending/processing/completed/failed와 만료 오류를 사용하고, 건강 동기화 POST는 동기 저장 후 200/succeeded로 완료된다. 개별 API 계약을 우선한다.\n',1)
text=text.replace('## 2.1 인증·요청 헤더','## 2.1 인증·요청 헤더\n\n아래 멱등성 적용 목록은 설계를 포함한다. 현재 구현에서 약관 동의·온보딩 완료는 비어 있지 않은 문자열 키를 받으며, 회원가입·로그아웃·검진 OCR·연결 등록·건강 쓰기·상담 생성/스트림은 UUID 키를 받는다. 로그아웃은 키를 받지만 응답을 별도 저장하지 않는다. 구현되지 않은 API에 이 정책이 이미 적용됐다고 간주하지 않는다.\n',1)
internal='''## POST /internal/chat/stream

**구현된 Spring 호출 계약**: 내부 Bearer 인증 및 X-Request-Id, application/json. 공개 앱이 직접 호출하지 않는다.

요청: `{contractVersion:"1.0", message, history:[{role,content}], summary, persona, personalContext}`. persona는 heapy_cat→coach, heapy_dog→professional로 변환한다. history 개별 content는 최대 2000자, summary는 최대 4000자, 직렬화 요청은 최대 262,144바이트다. 기존 rawQuery/recentMessages/companionCode/userContext 이름으로 전송하지 않는다.

내부 SSE의 status·delta·done·error를 처리한다. done에서 answer, responseStatus, summary, citations, metadata 및 허용된 diagnostics를 읽는다. 중단 전 delta가 있으면 partial로 처리하며 출처와 요약을 완전 응답처럼 확정하지 않는다. 공개 SSE는 앞의 상담 API 계약을 따른다.

**근거**: `ChatGateway`, `ChatController`.

---

## POST /internal/health/analyses

**신규 구현된 Spring 호출 계약**: 내부 Bearer 인증, application/json. 요청은 `contractVersion:"1.0"`, category, analysisDate, cutoff와 스냅샷의 sex, age, records, checkups다. 사용자 ID나 토큰을 분석 본문에 추가하지 않는다.

응답 status는 generated 또는 data_insufficient이며 generated는 headline이 있는 report 객체가 필요하다. 요청 최대 262,144바이트, 응답 최대 65,536바이트, 연결 제한 5초·읽기 제한 90초이며 자동 HTTP 재시도를 하지 않는다. 사용자·날짜·분류별 실행권으로 중복 생성을 막는다. 당일 조회 API는 별도로 결과만 읽는다.

**근거**: `HealthAnalysisRunner`, `HealthAnalysisSnapshot`, `HealthAnalysisGateway`.

---

'''
text=re.sub(r'^## POST /internal/chat/stream\n.*?(?=^# 12부\.)',lambda m:internal,text,flags=re.M|re.S)
text=text.replace('## POST /internal/ocr\n', '## POST /internal/ocr\n\n**설계 보존**: 현재 건강검진 OCR의 실행 경로는 Lambda Invoke다. 아래 HTTP API는 기존 설계로 남기며 구현된 공개 검진 API의 실제 호출 경로로 간주하지 않는다.\n',1)
codes=Path(root/'src/main/java/com/heapy/common/exception/ErrorCode.java').read_text(encoding='utf-8')
status={'PAYLOAD_TOO_LARGE':413,'FORBIDDEN':403,'CONFLICT':409,'SERVICE_UNAVAILABLE':503,'UNSUPPORTED_MEDIA_TYPE':415,'GONE':410,'UNPROCESSABLE_CONTENT':422,'BAD_REQUEST':400,'METHOD_NOT_ALLOWED':405,'INTERNAL_SERVER_ERROR':500,'UNAUTHORIZED':401,'TOO_MANY_REQUESTS':429,'NOT_FOUND':404}
rows=[]
for http,code,message in re.findall(r'\(HttpStatus\.(\w+), "([^"]+)", "([^"]+)"\)',codes):
    rows.append(f'| {status[http]} | {code} | {message} |')
errors='''## 12.3 현재 구현된 오류 코드 대조표

12.1~12.2는 미구현 기능의 오류 설계를 포함해 보존한다. **구현 API의 실제 코드·HTTP 상태는 아래 표를 기준으로 한다.** 예를 들어 건강 입력 검증은 기존 HEALTH-001~004 설계 대신 COMMON-001, 실제 동기화 용량 제한은 HEALTH-009를 사용한다. 검진 비교의 잘못된 ID 개수는 CHECKUP-002가 아니라 COMMON-001이다.

| HTTP | 코드 | 현재 메시지 |
| --- | --- | --- |
'''+ '\n'.join(rows)+'\n\n---\n\n'
text=text.replace('# 13부. 트랜잭션·멱등성·보존',errors+'# 13부. 트랜잭션·멱등성·보존',1)
text=text.replace('# 13부. 트랜잭션·멱등성·보존', '# 13부. 트랜잭션·멱등성·보존\n\n기존 미구현 기능의 설계 표를 유지한다. 현재 생활 건강 쓰기는 API별 계약대로 원자 저장하고, 일일 건강 분석 본문은 임시 캐시에 두며 실행 이력과 구분한다. 기존 물 입력 수정·삭제는 현재 구현되어 있으므로 과거 기록 수정·삭제 금지의 예외다. 다른 직접 입력의 수정·삭제 API는 아직 구현하지 않았다.\n',1)
text += '\n\n## 2026-09-11 · v1.2 구현 계약 대조\n\n작성자: 김진우. 구현된 API의 현재 요청·응답·오류를 갱신하고 새 동기화 상태·물 관리·당일 분석 경로를 추가했다. 미구현 API의 본문은 기존 설계를 그대로 보존했다. 온보딩 일괄 저장 등 백엔드 문서에만 있던 확정 변경도 Reference 사본에 함께 반영했다. 코드나 DB 변경, 커밋·푸시·배포는 하지 않았다.\n'
after=sections(text)
preserved=[key for key in old if key not in updates]
for key in preserved:
    assert old[key]==after[key],f'보존 API 변경: {key}'
assert all(k in after for k in old)
assert len(after)==len(set(after))
assert text.count('~~~')%2==0
(stage/'갱신본.md').write_text(text,encoding='utf-8')
path.write_text(text,encoding='utf-8')
print(json.dumps({'updated':len(set(updates)&set(old)),'added':len(set(updates)-set(old)),'preserved':len(preserved),'total':len(after)},ensure_ascii=False))
