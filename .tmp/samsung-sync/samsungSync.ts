import { AppState } from 'react-native';
import { healthSyncApi, SyncBatch } from './healthSyncApi';
import { getSamsungPermissions, hasRequiredSamsungPermissions, readSamsungHealthPage } from './samsungHealth';
import { SamsungPermission } from './types';
import { createIdempotencyKey } from '../../shared/utils/idempotency';

const STREAMS = ['sleep', 'heart_rate', 'blood_glucose', 'blood_pressure', 'body_composition', 'exercise', 'activity', 'water', 'nutrition'] as const;
const LABELS = ['수면', '심박수', '혈당', '혈압', '체성분', '운동', '활동', '물', '영양'];
const DAY = 86400000;
type Result = { connected: boolean; message: string; receivedCount: number };
type Options = { permission?: SamsungPermission; automatic?: boolean; onProgress?: (text: string) => void };
let flight: { auth: string; promise: Promise<Result> } | undefined;
let recent: { auth: string; at: number } | undefined;
const day = (time: number) => new Date(time + 9 * 3600000).toISOString().slice(0, 10);
const shiftDay = (date: string, days: number) => new Date(Date.parse(date + 'T00:00:00Z') + days * DAY).toISOString().slice(0, 10);

/** 완료된 항목만 체크포인트를 이동한다. 건강 원본은 디스크에 임시 저장하지 않는다. 작성자: 김진우 */
export async function syncSamsungHealth(options: Options = {}): Promise<Result> {
  const auth = await healthSyncApi.session();
  if (flight?.auth === auth) return flight.promise;
  if (options.automatic && recent?.auth === auth && Date.now() - recent.at < 15 * 60000)
    return { connected: true, message: '', receivedCount: 0 };
  const promise = execute(auth, options);
  flight = { auth, promise };
  try { return await promise; }
  finally { if (flight?.promise === promise) flight = undefined; }
}

async function execute(auth: string, options: Options): Promise<Result> {
  const guard = async () => {
    if (await healthSyncApi.session() !== auth) throw new Error('로그인 계정이 변경되어 동기화를 중단했어요.');
    if (AppState.currentState && AppState.currentState !== 'active') throw new Error('앱을 열어 두면 건강 기록 동기화를 이어갈 수 있어요.');
  };
  await guard();
  if (!options.permission && !(await healthSyncApi.connections(auth)).length)
    return { connected: false, message: '저장된 기록을 새로 불러왔어요. 삼성헬스 연결은 마이탭에서 시작할 수 있어요.', receivedCount: 0 };
  const permission = options.permission ?? await getSamsungPermissions();
  await guard();
  // 작성자: 김진우 — 다른 휴대폰의 연결 상태를 현재 설치의 연결로 사용하지 않는다.
  if (!options.permission && !(await healthSyncApi.connections(auth)).some(c => c.deviceInstallationId === permission.deviceInstallationId))
    return { connected: false, message: '이 휴대폰의 삼성헬스를 마이탭에서 연결해 주세요.', receivedCount: 0 };
  const connection = await healthSyncApi.register(auth, permission);
  if (!hasRequiredSamsungPermissions(permission.grantedDataTypes) || connection.status !== 'connected')
    throw new Error('삼성헬스의 11개 읽기 권한을 모두 허용한 뒤 다시 동기화해 주세요.');
  const state = await healthSyncApi.state(auth, connection.connectionId);
  const cutoff = new Date(Math.min(Date.now(), Date.parse(state.serverTime))).toISOString();
  const cutoffMs = Date.parse(cutoff);
  const fromDay = shiftDay(day(cutoffMs), -364), toDay = day(cutoffMs);
  const historyStart = new Date(fromDay + 'T00:00:00+09:00').toISOString();
  let receivedCount = 0;
  const save = async (body: SyncBatch) => {
    const key = createIdempotencyKey();
    for (let attempt = 0; ; attempt++) {
      await guard();
      try { const result = await healthSyncApi.save(auth, body, key); receivedCount += result.receivedCount; return; }
      catch (error) {
        const status = (error as { status?: number }).status;
        if (attempt >= 2 || (status !== 0 && status !== 502 && status !== 503 && status !== 504)) throw error;
        await new Promise<void>(resolve => setTimeout(resolve, 500 * 2 ** attempt));
      }
    }
  };
  for (const [index, dataType] of STREAMS.entries()) {
    options.onProgress?.(`${LABELS[index]} 기록을 동기화하고 있어요 (${index + 1}/${STREAMS.length})`);
    const base = { connectionId: connection.connectionId, dataType, syncMode: options.automatic ? 'foreground' as const : 'manual_refresh' as const };
    if (dataType === 'activity') {
      // 작성자: 김진우 — 변경 이력이 없는 일별 집계는 최근 1년을 다시 읽어 과거 수정·삭제도 반영한다.
      for (let from = fromDay; from <= toDay; from = shiftDay(from, 30)) {
        const to = [shiftDay(from, 29), toDay].sort()[0];
        await guard();
        const page = await readSamsungHealthPage({ dataType, from, to, cutoff, changes: false });
        await save({ ...base, records: page.records, ...(to === toDay ? { through: cutoff } : {}) });
      }
    } else {
      const cursor = state.cursorState[dataType];
      // 작성자: 김진우 — 변경 로그를 놓치지 않도록 1분 겹쳐 읽는다. 오래된 체크포인트도 구간을 나눠 읽는다.
      let from = cursor ? new Date(Date.parse(cursor) - 60000).toISOString() : historyStart;
      let first = !cursor;
      while (Date.parse(from) < cutoffMs) {
        const to = new Date(Math.min(Date.parse(from) + 365 * DAY, cutoffMs)).toISOString();
        let pageToken: string | undefined;
        const tokens = new Set<string>();
        do {
          await guard();
          const page = await readSamsungHealthPage({ dataType, from, to, changes: !first, pageToken });
          if (page.nextPageToken && tokens.has(page.nextPageToken)) throw new Error('삼성헬스가 같은 페이지를 반복해서 반환했어요. 다시 동기화해 주세요.');
          if (page.nextPageToken) tokens.add(page.nextPageToken);
          await save({ ...base, records: page.records, ...(!page.nextPageToken ? { through: to } : {}) });
          pageToken = page.nextPageToken || undefined;
        } while (pageToken);
        from = to; first = false;
      }
    }
  }
  await guard();
  recent = { auth, at: Date.now() };
  return { connected: true, message: '삼성헬스 건강 기록을 동기화했어요.', receivedCount };
}
