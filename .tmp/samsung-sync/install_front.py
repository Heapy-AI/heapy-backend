from pathlib import Path
import shutil
root = Path('C:/Users/jinwo/heapy-frontend')
stage = Path(__file__).parent
for name in ['healthSyncApi.ts', 'samsungSync.ts']:
    shutil.copyfile(stage / name, root / 'src/features/dataConnection' / name)
def edit(path, old, new):
    p = root / path
    s = p.read_text(encoding='utf-8')
    assert old in s, path + ': ' + old[:60]
    p.write_text(s.replace(old, new), encoding='utf-8')
edit('src/features/dataConnection/types.ts', 'export type HealthConnection = {', 'export type HealthConnection = {\n  deviceInstallationId?: string;')
p = root / 'src/features/dataConnection/samsungHealth.ts'
p.write_text(p.read_text(encoding='utf-8') + '''

export type HealthPageOptions = { dataType: string; from: string; to: string; changes: boolean; pageToken?: string; cutoff?: string };
export async function getSamsungPermissions(): Promise<SamsungPermission> {
  if (Platform.OS !== 'android' || !NativeModules.HeapySamsungHealth?.getReadPermissions)
    throw new Error('건강 기록 동기화가 포함된 최신 Android 앱으로 업데이트해 주세요.');
  return NativeModules.HeapySamsungHealth.getReadPermissions();
}
export async function readSamsungHealthPage(options: HealthPageOptions): Promise<{ records: SyncRecord[]; nextPageToken?: string | null }> {
  if (Platform.OS !== 'android' || !NativeModules.HeapySamsungHealth?.readHealthPage)
    throw new Error('건강 기록 동기화가 포함된 최신 Android 앱으로 업데이트해 주세요.');
  return NativeModules.HeapySamsungHealth.readHealthPage(options);
}
''', encoding='utf-8')
edit('src/features/dataConnection/samsungHealth.ts', "import { SamsungPermission, SamsungSteps }", "import type { SyncRecord } from './healthSyncApi';\nimport { SamsungPermission, SamsungSteps }")
edit('src/shared/api/client.ts', '  if (tokens)\n    config.headers.Authorization', '''  const expected = config.headers.Authorization;
  const actual = tokens ? `${tokens.tokenType || 'Bearer'} ${tokens.accessToken}` : undefined;
  if (expected && expected !== actual) throw new Error('로그인 계정이 변경되어 요청을 중단했어요.');
  if (tokens)
    config.headers.Authorization''')
edit('src/shared/api/client.ts', '    if (isUnauthorizedStatus(status)) {', '''    const currentTokens = await tokenStorage.get();
    const currentAuthorization = currentTokens ? `${currentTokens.tokenType || 'Bearer'} ${currentTokens.accessToken}` : undefined;
    if (isUnauthorizedStatus(status) && error.config?.headers.Authorization === currentAuthorization) {''')
(root / 'src/features/health/healthRefresh.ts').write_text('''// 작성자: 김진우 — 마이탭과 내 건강이 동일한 SDK → 서버 저장 경로를 사용한다.
import { syncSamsungHealth } from '../dataConnection/samsungSync';
export const refreshSamsungConnection = (onProgress?: (text: string) => void) => syncSamsungHealth({ onProgress });
''', encoding='utf-8')
edit('src/features/dataConnection/DataConnectionScreen.tsx', "import { createIdempotencyKey } from '../../shared/utils/idempotency';", "import { syncSamsungHealth } from './samsungSync';")
edit('src/features/dataConnection/DataConnectionScreen.tsx', '  const [permissionGranted', "  const [syncProgress, setSyncProgress] = useState('');\n  const [permissionGranted")
edit('src/features/dataConnection/DataConnectionScreen.tsx', '''      const connection = await dataConnectionApi.connectSamsung(
        permissions,
        createIdempotencyKey(),
      );''', '''      const connection = await syncSamsungHealth({ permission: permissions, onProgress: setSyncProgress });''')
edit('src/features/dataConnection/DataConnectionScreen.tsx', "connection.status !== 'connected'", '!connection.connected')
edit('src/features/dataConnection/DataConnectionScreen.tsx', '''    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['health-connections'] });
    },''', '''    onSuccess: () => {
      setSyncProgress('삼성헬스 건강 기록을 동기화했어요.');
      client.invalidateQueries({ queryKey: ['health-connections'] });
      client.invalidateQueries({ queryKey: ['health'] });
    },
    onError: () => {
      setSyncProgress('');
      client.invalidateQueries({ queryKey: ['health-connections'] });
      client.invalidateQueries({ queryKey: ['health'] });
    },''')
edit('src/features/dataConnection/DataConnectionScreen.tsx', '''          현재 연결 확인은 읽기 권한과 오늘 걸음 수를 확인해요. 내 건강의 전체
          기록을 서버로 전송하는 기능은 아직 지원하지 않아요.''', '''          처음 연결하면 최근 1년의 건강 기록을 가져와요. 이후에는 내 건강에서
          아래로 당겨 최신 기록을 동기화할 수 있어요.''')
edit('src/features/dataConnection/DataConnectionScreen.tsx', '        {todaySteps && (', '        {!!syncProgress && <Text accessibilityLiveRegion="polite" style={s.description}>{syncProgress}</Text>}\n        {todaySteps && (')
edit('src/features/dataConnection/DataConnectionScreen.tsx', "'연결 권한 다시 확인'", "'건강 기록 다시 동기화'")
edit('src/features/dataConnection/DataConnectionScreen.tsx', '읽기 권한은 허용됐지만 연결 정보를 저장하지 못했어요. ', '읽기 권한은 허용됐지만 건강 기록 동기화를 완료하지 못했어요. ')
edit('src/features/health/HealthScreen.tsx', "import { refreshSamsungConnection } from './healthRefresh';", "import { refreshSamsungConnection } from './healthRefresh';\nimport { syncSamsungHealth } from '../dataConnection/samsungSync';")
edit('src/features/health/HealthScreen.tsx', 'await refreshSamsungConnection();', 'await refreshSamsungConnection(setSyncNotice);')
edit('src/features/health/HealthScreen.tsx', '// 작성자: 김진우 — 마이탭과 같은 연결 정보를 조회하고 실제 읽기 지원 범위만 안내한다.', '// 작성자: 김진우 — 실제 동기화가 완료된 뒤 서버 기록을 다시 조회한다.')
old='''    const timer = setInterval(
      () => client.invalidateQueries({ queryKey: ['health', 'page'] }),
      15 * 60000,
    );
    const app = AppState.addEventListener('change', state => {
      if (state === 'active')
        client.invalidateQueries({ queryKey: ['health'] });
    });'''
new='''    let mounted = true;
    const automatic = async () => {
      if (AppState.currentState && AppState.currentState !== 'active') return;
      try {
        await syncSamsungHealth({ automatic: true });
        await client.invalidateQueries({ queryKey: ['health'] });
        await client.invalidateQueries({ queryKey: ['health-connections'] });
      } catch (error) {
        if (mounted) setSyncError(error instanceof Error ? error.message : '건강 기록 동기화를 완료하지 못했어요.');
      }
    };
    void automatic();
    const timer = setInterval(() => { void automatic(); }, 15 * 60000);
    const app = AppState.addEventListener('change', state => {
      if (state === 'active') void automatic();
    });'''
edit('src/features/health/HealthScreen.tsx',old,new)
edit('src/features/health/HealthScreen.tsx', '      clearInterval(timer);\n      app.remove();', '      mounted = false;\n      clearInterval(timer);\n      app.remove();')
p=root/'android/app/src/main/java/com/heapy/app/SamsungHealthReader.kt'
s=p.read_text(encoding='utf-8').replace('maxOf(point.updateTime, change.changeTime)', 'maxOf(requireNotNull(point.updateTime), change.changeTime)')
s=s.replace('record(type, it, it.updateTime)', 'record(type, it, requireNotNull(it.updateTime))').replace('suspend fun <T> collect', 'suspend fun <T : Any> collect').replace('flatMap { it.stages }', 'flatMap { it.stages.orEmpty() }')
p.write_text(s,encoding='utf-8')
