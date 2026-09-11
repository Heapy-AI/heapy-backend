from pathlib import Path
p=Path('C:/Users/jinwo/heapy-frontend')
f=p/'src/features/health/HealthChart.tsx';s=f.read_text(encoding='utf-8')
s=s.replace("{table ? '수치표 접기' : '수치표 보기'}{' '}\n            <Text style={s.chevron}>{table ? '⌃' : '⌄'}</Text>","{table ? '수치표 접기' : '수치표 보기'}")
f.write_text(s,encoding='utf-8')
f=p/'preview/mockHealthApi.ts';s=f.read_text(encoding='utf-8')
s=s.replace('.filter(r => r.date >= from && r.date <= to)',".filter(r => r.date >= from && r.date <= to)\n            .filter(r => new URLSearchParams(location.search).get('healthSparse') !== '1' || metric !== 'bio' || Number(r.date.slice(-2)) % 2 === 0)")
f.write_text(s,encoding='utf-8')
(p/'__tests__/healthPresentation.test.ts').write_text('''// 작성자: 김진우 — 선택 기본값과 결측 표현에 쓰는 실제 선분·축 계산 검증.
import {axisMaximum,lineSegments} from '../src/features/health/chartGeometry';
test('연속 날짜는 실선, 중간 기록이 없는 날짜는 점선이며 값을 추가하지 않는다',()=>{
 const points=['2026-09-01','2026-09-02','2026-09-04'].map((date,i)=>({date,value:i+10,recordedDays:1,spanDays:1,coveredDays:1}));
 const segments=lineSegments(points,['2026-09-01','2026-09-02','2026-09-03','2026-09-04']);
 expect(segments.map(s=>s.gap)).toEqual([false,true]);
 expect(segments.map(s=>s.to.value)).toEqual([11,12]);
 expect(lineSegments([points[0]!],['2026-09-01'])).toEqual([]);
});
test('축 최댓값은 실제 값을 자르지 않고 읽기 쉬운 눈금으로 올린다',()=>{
 for(const value of [0,0.2,4,68,125,1200,13250]) expect(axisMaximum(value)).toBeGreaterThan(value);
});
''',encoding='utf-8')
(p/'__tests__/healthRefresh.test.ts').write_text('''// 작성자: 김진우 — 권한 연결을 전체 기록 전송 성공으로 바꾸지 않는 새로고침 계약.
import {refreshSamsungConnection} from '../src/features/health/healthRefresh';
import {dataConnectionApi} from '../src/features/dataConnection/dataConnectionApi';
import {readSamsungTodaySteps} from '../src/features/dataConnection/samsungHealth';
jest.mock('../src/features/dataConnection/dataConnectionApi',()=>({dataConnectionApi:{getConnections:jest.fn()}}));
jest.mock('../src/features/dataConnection/samsungHealth',()=>({hasRequiredSamsungPermissions:(types:string[])=>types.includes('all'),readSamsungTodaySteps:jest.fn()}));
const connections=jest.mocked(dataConnectionApi.getConnections), read=jest.mocked(readSamsungTodaySteps);
beforeEach(()=>jest.clearAllMocks());
test('연결이 없으면 기기를 읽지 않고 서버 기록 갱신 안내를 반환한다',async()=>{
 connections.mockResolvedValue([]);
 expect((await refreshSamsungConnection()).connected).toBe(false);
 expect(read).not.toHaveBeenCalled();
});
test('연결된 사용자는 마이탭과 같은 브리지로 읽고 전체 전송 완료를 주장하지 않는다',async()=>{
 connections.mockResolvedValue([{connectionId:'test',status:'connected',grantedDataTypes:['all'],lastSyncedAt:null}]);
 read.mockResolvedValue({date:'2026-09-10',steps:3,hasData:true,readAt:'2026-09-10T00:00:00Z'});
 const result=await refreshSamsungConnection();
 expect(read).toHaveBeenCalledTimes(1);
 expect(result.message).toContain('전체 기록의 서버 전송은 아직 지원하지 않아요');
});
test('기기 권한 철회와 읽기 오류는 성공 안내로 바꾸지 않는다',async()=>{
 connections.mockResolvedValue([{connectionId:'test',status:'connected',grantedDataTypes:['all'],lastSyncedAt:null}]);
 read.mockRejectedValue(new Error('권한 철회'));
 await expect(refreshSamsungConnection()).rejects.toThrow('권한 철회');
});
''',encoding='utf-8')
(p/'preview/tests/건강디자인.spec.ts').write_text('''// 작성자: 김진우 — 터치 말풍선, 동일 크기 점, 결측 연결, 동작 줄이기 및 연결 상태 검증.
import {test,expect} from '@playwright/test';
test.use({viewport:{width:390,height:844}});
async function health(page:import('@playwright/test').Page,params='') {
 await page.goto('/?frame=1'+params+'#Home');
 await page.getByRole('tab',{name:'내 건강',exact:true}).click();
}
test('기본 설명은 숨기고 터치한 실측값만 말풍선으로 보인다',async({page})=>{
 await page.emulateMedia({reducedMotion:'reduce'});
 await health(page,'&healthSparse=1');
 await page.screenshot({path:'artifacts/health-polished-overview.png'});
 await page.getByRole('button',{name:/생체 기록/}).click();
 const chart=page.getByTestId('health-chart-체중 변화');
 await expect(chart).toBeVisible();
 await expect(page.getByText(/밀어서 이동|두 손가락으로 확대/)).toHaveCount(0);
 await expect(page.getByTestId('health-chart-tooltip')).toHaveCount(0);
 await expect(chart.getByTestId('health-gap-segment').first()).toBeAttached();
 const sizes=await chart.getByTestId('health-chart-point').evaluateAll(nodes=>nodes.map(n=>n.getAttribute('r')));
 expect(new Set(sizes)).toEqual(new Set(['3.5']));
 await chart.getByTestId('health-chart-hit').last().click();
 await expect(chart.getByTestId('health-chart-tooltip')).toBeVisible();
 await expect(chart.getByTestId('health-chart-tooltip')).toContainText('kg');
 await chart.scrollIntoViewIfNeeded();
 await page.screenshot({path:'artifacts/health-polished-tooltip.png'});
 await chart.getByRole('button',{name:'수치 말풍선 닫기'}).click();
 await expect(chart.getByTestId('health-chart-tooltip')).toHaveCount(0);
 await chart.getByTestId('health-chart-hit').first().click();
 await page.getByRole('radio',{name:'30일',exact:true}).click();
 await expect(page.getByTestId('health-chart-tooltip')).toHaveCount(0);
});
test('홈과 같은 분석 물결은 움직이며 동작 줄이기에서는 멈춘다',async({page})=>{
 await page.emulateMedia({reducedMotion:'no-preference'});
 await health(page);
 const wave=page.getByTestId('health-analysis-wave-motion');
 await expect(wave).toBeAttached();
 const initial=await wave.evaluate(n=>getComputedStyle(n).transform);
 await expect.poll(()=>wave.evaluate(n=>getComputedStyle(n).transform)).not.toBe(initial);
 await page.emulateMedia({reducedMotion:'reduce'});
 await expect(wave).toHaveCount(0);
 await page.getByRole('button',{name:/활동 기록/}).click();
 await expect.poll(()=>page.getByTestId('health-chart-걸음 추이').getByTestId('health-chart-bar').first().getAttribute('height')).not.toBe('0');
});
test('마이탭 연결 후 새로고침은 없는 전체 동기화 함수를 호출하지 않는다',async({page})=>{
 await page.goto('/?frame=1#Home');
 await page.getByRole('tab',{name:'마이',exact:true}).click();
 await page.getByRole('button',{name:'삼성헬스 연동',exact:true}).click();
 await page.getByRole('button',{name:'삼성헬스 연결하기',exact:true}).click();
 await expect(page.getByText('오늘 4,620걸음',{exact:true})).toBeVisible();
 await page.getByRole('button',{name:'이전 화면',exact:true}).click();
 await page.getByRole('tab',{name:'내 건강',exact:true}).click();
 await page.getByRole('button',{name:'건강 기록 새로고침',exact:true}).click();
 await expect(page.getByText(/삼성헬스 연결과 오늘 걸음 읽기를 확인했어요/)).toBeVisible();
 await expect(page.getByText(/전체 기록 동기화 연결이 아직 준비되지/)).toHaveCount(0);
});
''',encoding='utf-8')
