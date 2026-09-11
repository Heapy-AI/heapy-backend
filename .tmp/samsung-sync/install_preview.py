from pathlib import Path
import shutil
root=Path('C:/Users/jinwo/heapy-frontend'); stage=Path(__file__).parent
shutil.copyfile(stage/'samsungSync.test.ts',root/'__tests__/samsungSync.test.ts')
shutil.copyfile(stage/'mockHealthSyncApi.ts',root/'preview/mockHealthSyncApi.ts')
p=root/'preview/vite.config.mts'
s=p.read_text(encoding='utf-8').replace('      resolveId(source) {', "      resolveId(source) {\n        if (/\\/healthSyncApi$/.test(source)) return local('./mockHealthSyncApi.ts');")
p.write_text(s,encoding='utf-8')
p=root/'preview/mockDataConnection.ts'; s=p.read_text(encoding='utf-8').replace("connectionId: 'preview-samsung',", "connectionId: 'preview-samsung',\n        deviceInstallationId: payload.deviceInstallationId,")
p.write_text(s,encoding='utf-8')
p=root/'preview/mockSamsungHealth.ts'
p.write_text(p.read_text(encoding='utf-8')+'''
export const getSamsungPermissions = requestSamsungPermissions;
export const readSamsungHealthPage = async (_options: unknown) => ({ records: [], nextPageToken: null });
''',encoding='utf-8')
(root/'__tests__/healthRefresh.test.ts').write_text('''// 작성자: 김진우 — 새로고침은 공통 실제 동기화를 호출한다.
import { refreshSamsungConnection } from '../src/features/health/healthRefresh';
import { syncSamsungHealth } from '../src/features/dataConnection/samsungSync';
jest.mock('../src/features/dataConnection/samsungSync', () => ({ syncSamsungHealth: jest.fn() }));
test('동기화 완료와 오류를 호출 화면에 그대로 전달한다', async () => {
  const callback = jest.fn();
  const result = { connected: true, message: '동기화 완료', receivedCount: 2 };
  jest.mocked(syncSamsungHealth).mockResolvedValueOnce(result);
  expect(await refreshSamsungConnection(callback)).toEqual(result);
  expect(syncSamsungHealth).toHaveBeenCalledWith({ onProgress: callback });
  jest.mocked(syncSamsungHealth).mockRejectedValueOnce(new Error('전송 실패'));
  await expect(refreshSamsungConnection()).rejects.toThrow('전송 실패');
});
''',encoding='utf-8')
p=root/'preview/tests/건강디자인.spec.ts';s=p.read_text(encoding='utf-8').replace('마이탭 연결 후 새로고침은 없는 전체 동기화 함수를 호출하지 않는다','마이탭 연결 후 내 건강 새로고침이 공통 동기화를 완료한다').replace('삼성헬스 연결과 오늘 걸음 읽기를 확인했어요','삼성헬스 건강 기록을 동기화했어요')
p.write_text(s,encoding='utf-8')
