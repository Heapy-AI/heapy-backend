from pathlib import Path
p=Path('C:/Users/jinwo/heapy-frontend')
(p/'src/features/health/healthModel.test.ts').write_text('''// 작성자: 김진우 — 결측, 구간 평균, 열량 중복 계산을 검증한다.
import {activityCalories, coverage, macroSeries, exerciseKinds} from './healthModel';
import {HealthPage, HealthRecord} from './types';
const record = (date: string, values: HealthRecord['values']): HealthRecord => ({recordId: date + JSON.stringify(values), date, measuredAt: date + 'T00:00:00Z', source: 'samsung_health', editable: false, deletable: false, recordVersion: '1', values});
const page = (records: HealthRecord[]): HealthPage => ({metric: 'nutrition', period: {code: '90d', from: '2026-09-02', to: '2026-09-06', aggregation: 'week'}, timezone: 'Asia/Seoul', records, series: [], nextCursor: null, dataTruncated: false});
test('주간 분모는 기록일이며 부분 조회 구간 길이를 별도로 유지한다', () => {
 expect(coverage('2026-08-31', page([]).period)).toEqual({spanDays: 7, coveredDays: 5});
 const result = macroSeries(page([record('2026-09-02', {carbohydrate:10, protein:10,total_fat:10}),record('2026-09-04',{carbohydrate:30,protein:10,total_fat:10})]));
 expect(result[0]?.points[0]).toEqual({date:'2026-08-31',value:80,recordedDays:2,spanDays:7,coveredDays:5});
});
test('하루 중 영양소 누락이 있으면 해당 날짜 전체를 구성 비교에서 제외한다', () => {
 const r=macroSeries(page([record('2026-09-02',{carbohydrate:10,protein:10,total_fat:10}),record('2026-09-02',{carbohydrate:10,protein:10,total_fat:null})]));
 expect(r.every(s=>s.points.length===0)).toBe(true);
});
test('운동과 기타 활동은 일별로 분리하고 음수를 0으로 보정한다', () => {
 const a=page([record('2026-09-02',{active_calories_kcal:500}),record('2026-09-03',{active_calories_kcal:100})]);
 const e=page([record('2026-09-02',{calories_kcal:200}),record('2026-09-03',{calories_kcal:150})]);
 const r=activityCalories(a,e);
 expect(r[0]?.points[0]?.value).toBe(175);
 expect(r[1]?.points[0]?.value).toBe(150);
 expect(activityCalories({...a,dataTruncated:true},e)).toEqual([]);
});
test('운동 기간 밖의 날짜를 운동 없음으로 추정하지 않는다', () => {
 const a=page([record('2026-09-01',{active_calories_kcal:500})]);
 expect(activityCalories(a,page([])).every(s=>s.points.length===0)).toBe(true);
});
test('운동 종류는 기록된 날의 미실시 종류만 0으로 포함한다', () => {
 const r=exerciseKinds(page([record('2026-09-02',{exercise_type:'걷기',duration_seconds:600}),record('2026-09-04',{exercise_type:'달리기',duration_seconds:1200})]));
 expect(r.map(s=>s.points[0]?.value)).toEqual([5,10]);
 expect(r[0]?.points[0]?.recordedDays).toBe(2);
});
''',encoding='utf-8')
f=p/'preview/mockDataConnection.ts';s=f.read_text(encoding='utf-8')
insert='''
// 작성자: 김진우 — 건강 화면 비교 테스트에만 쓰는 합성 검진 두 회차.
const healthCheckups: CheckupDetail[] = ['2026-08-01', '2025-08-01'].map((date, index) => ({
  recordId: 'health-checkup-' + index, measuredAt: date, providerName: '합성 검진기관',
  results: [
    {itemCode:'GLUCOSE',itemName:'공복 혈당',value:index ? '110' : '95',numericValue:index ? 110 : 95,unit:'mg/dL',status:index ? '주의' : '정상'},
    {itemCode:'WEIGHT',itemName:'체중',value:index ? '154' : '70',numericValue:index ? 154 : 70,unit:index ? 'lb' : 'kg',status:'정상'},
  ], findings:[], overallOpinions:[],
}));
const healthFixture = () => new URLSearchParams(window.location.search).get('healthCheckups') === '1';
'''
s=s.replace('let confirmedRecord:',insert+'\nlet confirmedRecord:',1)
s=s.replace('  getCheckup: () =>\n    respond(() => {','  getCheckup: id =>\n    respond(() => {\n      if (healthFixture()) { const record = healthCheckups.find(r=>r.recordId===id); if (!record) throw new Error(\'합성 검진 없음\'); return structuredClone(record); }',1)
s=s.replace('      confirmedRecord\n        ?',"      healthFixture() ? healthCheckups.map(r=>({recordId:r.recordId,measuredAt:r.measuredAt,providerName:r.providerName,sourceType:'ocr',resultCount:r.results.length,confirmedAt:'2026-09-01T00:00:00Z'})) : confirmedRecord\n        ?",1)
f.write_text(s,encoding='utf-8')
f=p/'preview/tests/내건강.spec.ts';s=f.read_text(encoding='utf-8');s=s.replace('통합 화면에서 상세 그래프와 모든 입력 화면으로 이동한다','통합 화면에서 생체 그래프와 수면 입력으로 이동한다')
s+='''
test('검진 두 회차의 기관 판정과 단위 차이를 보존한다', async ({page}) => {
  await page.goto('/?frame=1&healthCheckups=1#Home');
  await page.getByRole('tab', {name:'내 건강',exact:true}).click();
  await page.getByRole('tab', {name:'건강검진',exact:true}).click();
  await page.getByText('과거 검진과 비교',{exact:true}).click();
  await expect(page.getByText(/주의 → 정상/)).toBeVisible();
  await expect(page.getByText(/단위가 달라 수치 비교 불가/)).toBeVisible();
  await page.screenshot({path:'artifacts/health-checkup-compare.png',fullPage:true});
  await page.getByRole('button',{name:'건강 상세 뒤로',exact:true}).click();
  await page.getByText('결과 전체 보기',{exact:true}).click();
  await expect(page.getByText('공복 혈당',{exact:true})).toBeVisible();
});
test('나머지 상세 영역과 입력 폼을 열 수 있다', async ({page}) => {
  await open(page);
  for (const [name,title] of [['활동 기록','활동 열량'],['영양 기록','영양소 구성'],['수면 수면시간','수면 단계']]) {
    await page.getByRole('button',{name:new RegExp(name!)}).click();
    await expect(page.getByText(title!,{exact:true})).toBeVisible();
    await page.getByRole('button',{name:'건강 상세 뒤로',exact:true}).click();
  }
  await page.getByRole('button',{name:'+ 수치 입력',exact:true}).click();
  for (const name of ['혈압 수축기','체성분 체중','혈당 공복']) {
    await page.getByRole('button',{name:new RegExp(name)}).click();
    await expect(page.getByText('저장',{exact:true})).toBeVisible();
    await page.getByRole('button',{name:'직접 입력 뒤로',exact:true}).click();
  }
});
'''
f.write_text(s,encoding='utf-8')
