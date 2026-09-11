from pathlib import Path
root=Path('C:/Users/jinwo/heapy-frontend')
p=root/'src/features/health/HealthEntry.tsx'
s=p.read_text(encoding='utf-8')
old=Path('C:/Users/jinwo/heapy-backend/.tmp/health-ui/HealthEntry.tsx').read_text(encoding='utf-8')
tail=old[old.index('function AmountWheel('):]
tail=tail.replace('payload:Record<string,unknown>;', '')
tail=tail.replace('body:string;key:string', 'body:string;key:string;payload:Record<string,unknown>')
tail=tail.replace("consumedAt:toInstant(day,koreanTime(new Date().toISOString()))", "consumedAt:new Date(new Date(day+'T00:00:00+09:00').getTime()+(Date.now()-new Date(koreanDay()+'T00:00:00+09:00').getTime())).toISOString()")
tail=tail.replace("try{const body=mode", "try{if(mode==='create')toInstant(day,koreanTime(new Date().toISOString()));const body=mode")
tail=tail.replace('const encoded=mode+JSON.stringify(body)', "const encoded=mode==='create'?JSON.stringify({mode,day,value}):mode+JSON.stringify(body)")
tail=tail.replace('key:createIdempotencyKey()}', 'key:createIdempotencyKey(),payload:body}')
tail=tail.replace("healthApi.create('water',body,request.current.key)", "healthApi.create('water',request.current.payload,request.current.key)")
s=s[:s.index('// 작성자: 김진우 — 직접 입력은 새 기록 추가만')] + tail
s=s.replace("import { PrimaryButton }", "import { ConfirmModal } from '../../shared/components/ConfirmModal';\nimport { PrimaryButton }")
p.write_text(s,encoding='utf-8')
p=root/'src/features/health/HealthScreen.tsx'
s=p.read_text(encoding='utf-8').replace('물 섭취 기록 추가 ›','물 섭취 기록 관리 ›').replace('섭취한 물의 양을 새 기록으로 추가해요.','앱에서 추가한 과거 기록도 편집·삭제할 수 있어요.')
p.write_text(s,encoding='utf-8')
p=root/'src/features/dataConnection/samsungSync.ts'
s=p.read_text(encoding='utf-8')
s=s.replace('  const guard = async () => {', '''  const timings: Record<string, number> = {};
  const measure = async <T,>(name: string, work: () => Promise<T>): Promise<T> => {
    const start = Date.now();
    try { return await work(); } finally { timings[name] = (timings[name] ?? 0) + Date.now() - start; }
  };
  const guard = async () => {''')
s=s.replace('(await healthSyncApi.session()) !== auth', "(await measure('session', () => healthSyncApi.session())) !== auth")
s=s.replace('const result = await healthSyncApi.save(auth, body, key);', "const result = await measure('save', () => healthSyncApi.save(auth, body, key));")
s=s.replace('const page = await readSamsungHealthPage({', "const page = await measure('sdk', () => readSamsungHealthPage({")
s=s.replace('          changes: false,\n        });', '          changes: false,\n        }));')
s=s.replace('            pageToken,\n          });', '            pageToken,\n          }));')
s=s.replace('  await guard();\n  recent =', "  // 작성자: 김진우 — 개발 환경에서 시간만 기록하며 토큰·건강값·식별자는 출력하지 않는다.\n  if (__DEV__) console.info('[HEAPY_SYNC_TIMING]', JSON.stringify(timings));\n  await guard();\n  recent =")
p.write_text(s,encoding='utf-8')
print('물 편집·삭제 복구 및 동기화 구간 시간 측정 추가 완료')
