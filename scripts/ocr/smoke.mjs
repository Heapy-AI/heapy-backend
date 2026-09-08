// 작성자: 김진우 — 합성 파일과 임시 계정으로 공개 OCR API를 검증하며 토큰·비밀번호는 출력하지 않는다.
import { readFile, writeFile } from 'node:fs/promises';
import { createHash, randomUUID } from 'node:crypto';

const base = 'https://13.125.12.94';
const credentials = JSON.parse(await readFile('.env.ocr-smoke.json', 'utf8'));
if (!credentials.email.startsWith('ocr-smoke-') || !credentials.email.endsWith('@example.invalid')) {
  throw new Error('임시 OCR 검증 계정만 사용할 수 있습니다.');
}
const source = await readFile('build/ocr-smoke/source.pdf');
if (createHash('sha256').update(source).digest('hex') !== '014555784ddde09fdfb758d474641335f024b2c64ac876e987a8848c048f313e') {
  throw new Error('승인된 합성 PDF와 일치하지 않습니다.');
}
const report = { checks: [], jobIds: [] };
let token;
const check = (condition, label) => {
  if (!condition) throw new Error(label + ' 실패');
  report.checks.push(label);
  console.log(label + ': 통과');
};
async function call(path, options = {}) {
  const response = await fetch(base + path, {
    ...options,
    headers: { ...(token ? { Authorization: 'Bearer ' + token } : {}), ...options.headers },
    signal: AbortSignal.timeout(65000),
  });
  const raw = await response.text();
  let body = null;
  try { body = raw ? JSON.parse(raw) : null; } catch { /* 응답 원문을 출력하지 않는다. */ }
  return { status: response.status, body };
}
function upload(key, bytes = source) {
  const body = new FormData();
  body.append('file', new Blob([bytes], { type: 'application/pdf' }), 'synthetic.pdf');
  body.append('inputType', 'pdf');
  return call('/api/checkups/ocr-jobs', { method: 'POST', headers: { 'Idempotency-Key': key }, body });
}
try {
  const login = await call('/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: credentials.email, password: credentials.password }),
  });
  check(login.status === 200 && login.body?.data?.accessToken, '임시 계정 로그인');
  token = login.body.data.accessToken;
  const key = randomUUID();
  const created = await upload(key);
  if (created.status !== 202) console.log('업로드 응답:', created.status, created.body?.code ?? '응답 없음');
  check(created.status === 202, '실제 파일 업로드 202');
  const job = created.body.data;
  report.jobIds.push(job.jobId);
  console.log('검증 작업:', job.jobId);
  const repeated = await upload(key);
  check(repeated.status === 202 && JSON.stringify(repeated.body.data) === JSON.stringify(job), '업로드 멱등성');
  const conflict = await upload(key, Buffer.concat([source, Buffer.from('\n% changed fixture')]));
  check(conflict.status === 409 && conflict.body?.code === 'IDEMPOTENCY_KEY_REUSED', '같은 키의 다른 파일 차단');
  const missing = await call('/api/checkups/ocr-jobs/' + randomUUID());
  check(missing.status === 404, '없는 작업 접근 차단');
  let current;
  let previous;
  const deadline = Date.parse(job.expiresAt);
  while (Date.now() < deadline) {
    const polled = await call('/api/checkups/ocr-jobs/' + job.jobId);
    if (polled.status !== 200) console.log('조회 응답:', polled.status, polled.body?.code ?? '응답 없음');
    check(polled.status === 200, '작업 조회');
    current = polled.body.data;
    if (current.status !== previous) {
      console.log('OCR 상태:', current.status, current.errorCode ?? '오류 없음');
      previous = current.status;
    }
    if (current.status === 'failed') throw new Error('실제 OCR 처리 실패: ' + current.errorCode);
    if (current.status === 'completed') break;
    await new Promise(resolve => setTimeout(resolve, 5000));
  }
  check(current?.status === 'completed' && current.result?.items?.length >= 4, '실제 OCR 완료 및 검진 항목 인식');
  const original = current.result;
  const glucose = original.items.find(item => item.itemCode === 'FASTING_GLUCOSE');
  check(glucose && Number(glucose.value) === 95, '합성 공복혈당 95 인식');
  const corrections = [];
  const seen = new Set();
  const results = [];
  for (const item of original.items) {
    if (!item.itemCode || seen.has(item.itemCode)) {
      corrections.push({ fieldKey: item.fieldKey, itemCode: item.itemCode,
        originalValue: item.value, correctedValue: '', correctionType: 'excluded' });
      continue;
    }
    seen.add(item.itemCode);
    const value = item === glucose ? '96' : item.value;
    if (value !== item.value) corrections.push({ fieldKey: item.fieldKey + '.value', itemCode: item.itemCode,
      originalValue: item.value, correctedValue: value, correctionType: 'value' });
    results.push({ itemCode: item.itemCode, value,
      numericValue: /^[+-]?\d+(\.\d+)?$/.test(value.trim()) ? Number(value) : null,
      unit: item.unit, status: item.status });
  }
  const confirmation = { measuredAt: original.measuredAt, providerName: original.providerName, results, corrections };
  const confirmKey = randomUUID();
  const confirm = body => call('/api/checkups/ocr-jobs/' + job.jobId + '/confirm', {
    method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': confirmKey },
    body: JSON.stringify(body),
  });
  const saved = await confirm(confirmation);
  if (saved.status !== 201) console.log('확정 응답:', saved.status, saved.body?.code ?? '응답 없음');
  check(saved.status === 201, '검수 결과 확정 201');
  report.recordId = saved.body.data.recordId;
  report.resultCount = saved.body.data.resultCount;
  const resaved = await confirm(confirmation);
  check(resaved.status === 201 && JSON.stringify(resaved.body.data) === JSON.stringify(saved.body.data), '확정 멱등성');
  check((await confirm({ ...confirmation, providerName: '다른 합성 기관' })).status === 409, '확정 후 같은 키의 변경 차단');
  check((await call('/api/checkups/ocr-jobs/' + job.jobId)).status === 410, '확정 후 임시 결과 조회 차단');
  const cancelTarget = await upload(randomUUID());
  check(cancelTarget.status === 202, '취소 검증용 합성 업로드');
  const cancelId = cancelTarget.body.data.jobId;
  report.jobIds.push(cancelId);
  check((await call('/api/checkups/ocr-jobs/' + cancelId, { method: 'DELETE' })).status === 204, '미확정 작업 취소');
  check((await call('/api/checkups/ocr-jobs/' + cancelId)).status === 410, '취소 후 임시 결과 조회 차단');
  report.completedAt = new Date().toISOString();
  await writeFile('build/ocr-smoke/report.json', JSON.stringify(report, null, 2));
  console.log('공개 API 합성 OCR 검증 완료');
} finally {
  if (token) {
    for (const id of report.jobIds) {
      await call('/api/checkups/ocr-jobs/' + id, { method: 'DELETE' }).catch(() => {});
    }
    await call('/api/auth/logout', { method: 'POST', headers: { 'Idempotency-Key': randomUUID() } }).catch(() => {});
  }
}
