from pathlib import Path
import re,json
root=Path('C:/Users/jinwo/heapy-backend')
stage=root/'.tmp/api-spec'
p=root/'docs/api/HEAPY_BACKEND_API_명세_v1.md'
s=p.read_text(encoding='utf-8')
s=s.replace('| 과거 건강 기록 | 보관, 수정·삭제 API 없음 |','| 과거 건강 기록 | 보관. 현재 앱 입력 물 기록에 한해 수정·삭제 API 제공, 삼성 원본은 읽기 전용 |')
s=s.replace('- 과거 건강 기록 수정·삭제','- 과거 건강 기록의 일반 수정·삭제 기능. 단, 앱 입력 물 기록 관리 API는 현재 구현된 예외')
s=s.replace('- 공유 DB 마이그레이션은 미적용이다. 로컬 합성 검증을 원격 적용·배포·OCR/프런트 종단 간 완료로 해석하지 않는다.', '- 당시 인계 시점에는 공유 DB 마이그레이션 미적용으로 기록했다. 이후 적용 결과는 백엔드 `docs/api/검진_버전2_DB적용_개발배포_결과.md`를 참조한다. 현재 문서 대조 자체는 재배포나 종단 간 검증을 수행한 것이 아니다.')
s=s.replace('| v1.1 | 2026-09-03', '| v1.2 | 2026-09-11 | 김진우 | 구현된 API 계약 갱신, 신규 경로 4개 추가, 미구현 API 설계 본문 보존 |\n| v1.1 | 2026-09-03',1)
s=s.replace('같은 폴더의 `검진_저장확장_공유계약.md`', '백엔드 `docs/api/검진_저장확장_공유계약.md`')
s=s.replace('세부 길이·식별자 검증은 백엔드 `docs/api/검진_저장확장_공유계약.md` 4~6절', '세부 길이·식별자 검증은 [검진 저장 확장 공유계약](C:/Users/jinwo/heapy-backend/docs/api/검진_저장확장_공유계약.md) 4~6절')
s=s.replace('done에서 answer, responseStatus, summary, citations, metadata 및 허용된 diagnostics를 읽는다.', 'done에서 answer, summary, citations, metadata 및 허용된 diagnostics를 읽고 유효한 완료 응답은 completed로 처리한다. citation의 내부 title을 공개 sourceTitle로 변환한다.')
s=s.replace('최대 20,000개 원본을 처리하고', '최대 20,000개 원본을 처리하고')
s=s.replace('요청이 이미 적용됐다고 간주하지 않는다.', '요청이 이미 적용됐다고 간주하지 않는다.')
fields=Path(root/'src/main/java/com/heapy/health/service/HealthSyncInput.java').read_text(encoding='utf-8')
def camel(x):
 parts=x.split('_'); return parts[0]+''.join(a.title() for a in parts[1:])
table='\n**UPSERT data의 허용 필드** (DELETE는 data 불필요):\n\n| metric | 허용 필드 |\n| --- | --- |\n'
for metric,values in re.findall(r'HealthMetric\.(\w+), "([a-z_ ]+)"',fields):
 table+=f'| {metric.lower()} | '+', '.join(camel(x) for x in values.split())+' |\n'
table+='''
필수값은 sleep=startAt/endAt/totalSleepMinutes, exercise=startAt/endAt/exerciseType/durationSeconds, water=consumedAt/amountMl, nutrition=consumedAt이다. bio는 measuredAt/bioType와 해당 유형 수치(심박, 혈당, 수축기·이완기 혈압, 체중)가 필요하다. activity는 recordDate와 하나 이상의 실제 수치가 필요하다. activity 원본 ID는 `activity:YYYY-MM-DD`, 나머지는 `dataType:원본ID`이며 전체 ID는 최대 256자다. operation 생략 시 UPSERT로 해석한다.

SDK 시각에는 현재보다 최대 300초의 오차를 허용한다. 정수 필드는 정수여야 하며 나머지 수치는 소수 4자리로 반올림한다. 누락된 선택값은 null로 반영하므로 부분 수정 요청으로 사용하지 않는다. 알 수 없는 data 필드는 거부한다.

'''
s=s.replace('**근거**: `HealthSyncController`, `HealthSyncInput`, `HealthSyncService`, `HealthSyncPayloadAdvice`.',table+'**근거**: `HealthSyncController`, `HealthSyncInput`, `HealthSyncService`, `HealthSyncPayloadAdvice`.',1)
p.write_text(s,encoding='utf-8')
(stage/'갱신본.md').write_text(s,encoding='utf-8')

# 작성자: 김진우 — 변경하지 않은 API 본문과 미구현 도메인 전체가 원문 그대로인지 검사한다.
old=(stage/'백엔드_원본.md').read_text(encoding='utf-8')
pattern=r'^### ((?:GET|POST|PATCH|PUT|DELETE) /\S+)\n.*?(?=^#{1,3} |\Z)'
def sections(t): return {m.group(1):m.group(0).strip() for m in re.finditer(pattern,t,re.M|re.S)}
before,after=sections(old),sections(s)
assert set(before)<=set(after)
for part,nextpart in [('8','9'),('9','10')]:
 exp=r'^# '+part+r'부\..*?(?=^# '+nextpart+r'부\.)'
 assert re.search(exp,old,re.M|re.S).group()==re.search(exp,s,re.M|re.S).group()
for key in before:
 if any(x in key for x in ['/api/missions','/api/users/medication','/api/shop','/api/coins','/api/notifications','/api/chat/suggested-actions']):
  assert before[key]==after[key],key
for code in re.findall(r'~~~json\n(.*?)\n~~~',s,re.S): json.loads(code)
assert len(re.findall(r'^### (?:GET|POST|PATCH|PUT|DELETE) ',s,re.M))==len(after)
print(json.dumps({'endpoints_before':len(before),'endpoints_after':len(after),'unchanged_bodies':sum(before[k]==after[k] for k in before),'mission_medication_sections':'unchanged','json_examples':'valid'}))
