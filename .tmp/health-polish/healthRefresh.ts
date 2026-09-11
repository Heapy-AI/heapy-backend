// 작성자: 김진우 — 연결 확인·기기 읽기·서버 전송을 구분한다. 없는 전체 동기화 함수를 호출하지 않는다.
import {dataConnectionApi} from '../dataConnection/dataConnectionApi';
import {hasRequiredSamsungPermissions, readSamsungTodaySteps} from '../dataConnection/samsungHealth';
export async function refreshSamsungConnection() {
  const connections = await dataConnectionApi.getConnections();
  const connected = connections.some(c=>c.status==='connected'&&hasRequiredSamsungPermissions(c.grantedDataTypes));
  if (!connected) return {connected:false, message:'저장된 건강 기록을 새로 불러왔어요. 삼성헬스 연결은 마이탭에서 확인할 수 있어요.'};
  await readSamsungTodaySteps();
  return {connected:true, message:'삼성헬스 연결과 오늘 걸음 읽기를 확인했어요. 전체 기록의 서버 전송은 아직 지원하지 않아요.'};
}
