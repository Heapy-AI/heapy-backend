import { apiClient } from '../../shared/api/client';
import { tokenStorage } from '../../shared/storage/tokenStorage';
import { HealthConnection, SamsungPermission } from './types';
import { createIdempotencyKey } from '../../shared/utils/idempotency';

export type SyncRecord = {
  metric: string;
  externalRecordId: string;
  sourceUpdatedAt: string;
  operation: 'UPSERT' | 'DELETE';
  data?: Record<string, string | number | boolean>;
};
export type SyncState = { cursorState: Record<string, string>; serverTime: string };
export type SyncBatch = {
  connectionId: string;
  dataType: string;
  syncMode: 'app_open' | 'manual_refresh' | 'foreground';
  through?: string;
  records: SyncRecord[];
};
export type SyncResult = { receivedCount: number; insertedCount: number; updatedCount: number; skippedCount: number; deletedCount: number };
const config = (authorization: string) => ({ headers: { Authorization: authorization }, timeout: 60000 });

// 작성자: 김진우 — 동기화 시작 계정의 인증을 고정한다. 요청 직전에 공통 인터셉터가 계정 변경을 검사한다.
export const healthSyncApi = {
  async session() {
    const tokens = await tokenStorage.get();
    if (!tokens) throw new Error('로그인 후 건강 기록을 동기화해 주세요.');
    return `${tokens.tokenType || 'Bearer'} ${tokens.accessToken}`;
  },
  async connections(auth: string) {
    return (await apiClient.get<HealthConnection[]>('/api/health-connections', config(auth))).data;
  },
  async register(auth: string, permission: SamsungPermission) {
    return (await apiClient.post<HealthConnection>('/api/health-connections/samsung', permission,
      { ...config(auth), headers: { ...config(auth).headers, 'Idempotency-Key': createIdempotencyKey() } })).data;
  },
  async state(auth: string, connectionId: string) {
    return (await apiClient.get<SyncState>('/api/health-sync-state', { ...config(auth), params: { connectionId } })).data;
  },
  async save(auth: string, body: SyncBatch, key: string) {
    return (await apiClient.post<SyncResult>('/api/health-sync-runs', body,
      { ...config(auth), headers: { ...config(auth).headers, 'Idempotency-Key': key } })).data;
  },
};
