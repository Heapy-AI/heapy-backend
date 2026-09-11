import type { healthSyncApi as live } from '../src/features/dataConnection/healthSyncApi';
import { dataConnectionApi } from './mockDataConnection';
const cursorState: Record<string, string> = {};
// 작성자: 김진우 — 브라우저는 합성 SDK 응답만 사용한다. 실제 건강정보를 전송하지 않는다.
export const healthSyncApi: typeof live = {
  session: async () => '합성-미리보기-세션',
  connections: () => dataConnectionApi.getConnections(),
  register: (_auth, permission) => dataConnectionApi.connectSamsung(permission, 'preview-key'),
  state: async () => ({ cursorState: { ...cursorState }, serverTime: new Date().toISOString() }),
  save: async (_auth, body) => {
    if (body.through) cursorState[body.dataType] = body.through;
    return { receivedCount: body.records.length, insertedCount: body.records.length, updatedCount: 0, skippedCount: 0, deletedCount: 0 };
  },
};
