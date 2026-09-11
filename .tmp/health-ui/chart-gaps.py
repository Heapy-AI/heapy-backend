from pathlib import Path
p=Path('C:/Users/jinwo/heapy-frontend/src/features/health')
f=p/'healthModel.ts';s=f.read_text(encoding='utf-8')
s+='''
// 작성자: 김진우 — 빈 날짜를 축에 남기되 값 0을 생성하지 않는다.
export function chartDates(series: Series[], period?: HealthPage['period']): string[] {
  const recorded = [...new Set(series.flatMap(s => s.points.map(p => p.date)))].sort();
  if (!recorded.length || !period || period.aggregation === 'raw') return recorded;
  const dates = new Set<string>();
  for (let day = Date.parse(period.from); day <= Date.parse(period.to); day += 86400000) {
    dates.add(bucket(new Date(day).toISOString().slice(0,10), period.aggregation));
  }
  return [...dates].sort();
}
// 작성자: 김진우 — 단계별 누락이 있는 날은 수면 구성에서 제외하고 같은 날짜 집합으로 평균한다.
export function sleepStages(page?: HealthPage): Series[] {
  if (!page || page.dataTruncated) return [];
  const fields = [['deep_sleep_minutes','깊은 수면'],['rem_sleep_minutes','렘 수면'],['light_sleep_minutes','얕은 수면'],['awake_minutes','깨어 있음']] as const;
  const days = [...new Set(page.records.map(r=>r.date))].filter(date => page.records.filter(r=>r.date===date).every(r=>fields.every(([key])=>numeric(r,key)!==null)));
  return fields.map(([key,label]) => {
    const groups = new Map<string,number[]>();
    for (const date of days) {
      const value = page.records.filter(r=>r.date===date).reduce((n,r)=>n+(numeric(r,key)??0),0);
      const b = bucket(date,page.period.aggregation);
      groups.set(b,[...(groups.get(b)??[]),value]);
    }
    return {key,label,unit:'분',dailyAggregation:'sum',points:[...groups].sort().map(([date,values])=>({date,value:values.reduce((a,b)=>a+b,0)/values.length,recordedDays:values.length,...coverage(date,page.period)}))};
  });
}
'''
f.write_text(s,encoding='utf-8')
f=p/'HealthChart.tsx';s=f.read_text(encoding='utf-8').replace("import { Series }", "import { HealthPage, Series }").replace("import { format }", "import { chartDates, format }")
s=s.replace('  tableSeries,','  tableSeries,\n  period,',1).replace('  tableSeries?: Series[];','  tableSeries?: Series[];\n  period?: HealthPage[\'period\'];')
start=s.index('  const dates = [');end=s.index('  const chartWidth',start)
s=s[:start]+'  const dates = chartDates(series, period);\n'+s[end:]
f.write_text(s,encoding='utf-8')
f=p/'HealthScreen.tsx';s=f.read_text(encoding='utf-8').replace('  sumToday,','  sumToday,\n  sleepStages,',1)
start=s.index("        series={series(data.sleep, [\n          'deep_sleep_minutes'")
end=s.index('\n      />',start)
s=s[:start]+"        series={sleepStages(data.sleep)}"+s[end:]
import re
def addperiod(m):
    block=m.group(0)
    source=re.search(r'data\.(bio|activity|exercise|nutrition|water|sleep)',block)
    if not source: return block
    return block.replace('<HealthChart','<HealthChart period={data.'+source.group(1)+"?.period}",1)
s=re.sub(r'<HealthChart[\s\S]*?\n\s*/>',addperiod,s)
f.write_text(s,encoding='utf-8')
t=Path('C:/Users/jinwo/heapy-frontend/__tests__/healthModel.test.ts');s=t.read_text(encoding='utf-8')
s="import {chartDates,sleepStages} from '../src/features/health/healthModel';\n"+s
s+='''
test('기록이 없는 날짜도 일별 축에는 남고 값은 생성하지 않는다',()=>{
 const p=page([record('2026-09-02',{carbohydrate:10,protein:10,total_fat:10})]);
 p.period.aggregation='day';
 const s=macroSeries(p);
 expect(chartDates(s,p.period)).toHaveLength(5);
 expect(s[0]?.points).toHaveLength(1);
});
test('수면 단계 하나가 누락된 날짜는 모든 단계 구성에서 제외한다',()=>{
 const r=sleepStages(page([record('2026-09-02',{deep_sleep_minutes:60,rem_sleep_minutes:60,light_sleep_minutes:120,awake_minutes:0}),record('2026-09-03',{deep_sleep_minutes:80,rem_sleep_minutes:null,light_sleep_minutes:150,awake_minutes:0})]));
 expect(r.map(s=>s.points[0]?.value)).toEqual([60,60,120,0]);
 expect(r.every(s=>s.points[0]?.recordedDays===1)).toBe(true);
});
'''
t.write_text(s,encoding='utf-8')
