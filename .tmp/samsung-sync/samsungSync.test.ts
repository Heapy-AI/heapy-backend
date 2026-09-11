import { syncSamsungHealth } from '../src/features/dataConnection/samsungSync';
import { healthSyncApi } from '../src/features/dataConnection/healthSyncApi';
import { getSamsungPermissions, readSamsungHealthPage } from '../src/features/dataConnection/samsungHealth';

jest.mock('react-native', () => ({ AppState: { currentState: 'active' } }));
jest.mock('../src/shared/utils/idempotency', () => ({ createIdempotencyKey: (() => { let n = 0; return () => `key-${++n}`; })() }));
jest.mock('../src/features/dataConnection/healthSyncApi', () => ({ healthSyncApi: { session: jest.fn(), connections: jest.fn(), register: jest.fn(), state: jest.fn(), save: jest.fn() } }));
jest.mock('../src/features/dataConnection/samsungHealth', () => ({ getSamsungPermissions: jest.fn(), hasRequiredSamsungPermissions: (types: string[]) => types.includes('all'), readSamsungHealthPage: jest.fn() }));
const api = jest.mocked(healthSyncApi), read = jest.mocked(readSamsungHealthPage);
const permission = { deviceInstallationId: 'device', grantedDataTypes: ['all'], sdkVersion: '1.1.0', permissionCheckedAt: '2026-08-01T00:00:00Z' };
const connection = { connectionId: 'connection', deviceInstallationId: 'device', status: 'connected', grantedDataTypes: ['all'], lastSyncedAt: null };
beforeEach(() => {
  jest.clearAllMocks();
  api.session.mockResolvedValue('session'); api.connections.mockResolvedValue([connection]); api.register.mockResolvedValue(connection);
  api.state.mockResolvedValue({ cursorState: {}, serverTime: '2026-08-01T10:00:00Z' });
  api.save.mockImplementation(async (_auth, batch) => ({ receivedCount: batch.records.length, insertedCount: 0, updatedCount: 0, skippedCount: 0, deletedCount: 0 }));
  jest.mocked(getSamsungPermissions).mockResolvedValue(permission);
  read.mockResolvedValue({ records: [], nextPageToken: null });
});
test('현재 휴대폰 연결이 없으면 다른 기기 연결로 동기화하지 않는다', async () => {
  api.connections.mockResolvedValue([{ ...connection, deviceInstallationId: 'other' }]);
  expect((await syncSamsungHealth()).connected).toBe(false);
  expect(read).not.toHaveBeenCalled(); expect(api.register).not.toHaveBeenCalled();
});
test('SDK 마지막 페이지 저장 후에만 항목 완료 지점을 보낸다', async () => {
  read.mockImplementation(async options => ({ records: [], nextPageToken: options.dataType === 'sleep' && !options.pageToken ? 'next' : null }));
  await syncSamsungHealth();
  const batches = api.save.mock.calls.map(call => call[1]);
  expect(batches[0]?.through).toBeUndefined(); expect(batches[1]?.through).toBe('2026-08-01T10:00:00.000Z');
  expect(new Set(batches.filter(batch => batch.through).map(batch => batch.dataType)).size).toBe(9);
  expect(read.mock.calls.filter(([options]) => options.dataType === 'activity')).toHaveLength(13);
});
test('실패한 읽기의 완료 지점을 저장하지 않는다', async () => {
  read.mockRejectedValueOnce(new Error('SDK 실패'));
  await expect(syncSamsungHealth()).rejects.toThrow('SDK 실패');
  expect(api.save).not.toHaveBeenCalled();
});
test('전송 응답이 유실되어도 같은 멱등성 키로 재시도한다', async () => {
  api.save.mockRejectedValueOnce({ status: 0 });
  await syncSamsungHealth();
  expect(api.save.mock.calls[0]?.[2]).toEqual(api.save.mock.calls[1]?.[2]);
  expect(api.save.mock.calls[0]?.[1]).toEqual(api.save.mock.calls[1]?.[1]);
});
test('기존 완료 지점에서 변경 로그를 읽고 계정 변경 시 전송을 멈춘다', async () => {
  api.state.mockResolvedValue({ cursorState: { sleep: '2026-07-31T10:00:00Z' }, serverTime: '2026-08-01T10:00:00Z' });
  read.mockImplementationOnce(async () => { api.session.mockResolvedValue('other-session'); return { records: [] }; });
  await expect(syncSamsungHealth()).rejects.toThrow('계정이 변경');
  expect(read.mock.calls[0]?.[0]).toMatchObject({ dataType: 'sleep', changes: true, from: '2026-07-31T09:59:00.000Z' });
  expect(api.save).not.toHaveBeenCalled();
});
test('권한 철회를 서버에 반영한 뒤 원본 읽기를 중단한다', async () => {
  jest.mocked(getSamsungPermissions).mockResolvedValue({ ...permission, grantedDataTypes: [] });
  await expect(syncSamsungHealth()).rejects.toThrow('11개');
  expect(api.register).toHaveBeenCalled(); expect(read).not.toHaveBeenCalled();
});
