import { HealthPage, HealthRecord, PeriodCode, Series } from './types';
export const periods: {code: PeriodCode; label: string}[] = [{code:'7d',label:'7일'},{code:'30d',label:'30일'},{code:'90d',label:'90일'},{code:'180d',label:'180일'},{code:'1y',label:'1년'}];
export const koreanDay = (now = new Date()) => new Date(now.getTime() + 9 * 3600000).toISOString().slice(0,10);
export const koreanTime = (value: string) => new Date(new Date(value).getTime() + 9 * 3600000).toISOString().slice(11,16);
export const format = (value: unknown, digits = 1): string => typeof value === 'number' && Number.isFinite(value) ? value.toLocaleString('ko-KR', {maximumFractionDigits: digits}) : '—';
export const numeric = (row: HealthRecord, key: string): number | null => typeof row.values[key] === 'number' ? row.values[key] as number : null;
export const latest = (page: HealthPage | undefined, key: string) => page?.records.find(row => numeric(row,key) !== null);
export const sumToday = (page: HealthPage | undefined, key: string) => {
  const rows = page?.records.filter(row => row.date === koreanDay() && numeric(row,key) !== null) ?? [];
  return rows.length ? rows.reduce((sum,row) => sum + (numeric(row,key) ?? 0),0) : null;
};
export function series(page: HealthPage | undefined, keys: string[], factor = 1, unit?: string): Series[] {
  return page?.series.filter(s => keys.includes(s.key)).map(s => ({...s, unit: unit ?? s.unit, points:s.points.map(p=>({...p,value:p.value*factor}))})) ?? [];
}
export function bucket(day: string, aggregation: HealthPage['period']['aggregation']) {
  if (aggregation === 'month') return day.slice(0,7)+'-01';
  if (aggregation !== 'week') return day;
  const d=new Date(day+'T00:00:00Z'); d.setUTCDate(d.getUTCDate()-(d.getUTCDay()+6)%7); return d.toISOString().slice(0,10);
}
// 작성자: 김진우 — 영양 구성은 세 영양소가 모두 있는 날짜만 평가한다. 열량과 영양소 열량을 구분한다.
export function macroSeries(page?: HealthPage): Series[] {
  if (!page || page.dataTruncated) return [];
  const fields=[['carbohydrate','탄수화물',4],['protein','단백질',4],['total_fat','지방',9]] as const;
  const days=new Map<string, number[] | null>();
  for (const row of page.records) {
    if (fields.some(([key])=>numeric(row,key) === null)) {days.set(row.date,null);continue;}
    if (days.has(row.date) && days.get(row.date)===null) continue;
    const prior=days.get(row.date) ?? [0,0,0]; days.set(row.date,fields.map(([key,,factor],i)=>(prior[i]??0)+(numeric(row,key)??0)*factor));
  }
  return fields.map(([key,label],index)=> {
    const buckets=new Map<string,number[]>();
    days.forEach((values,date)=>{if(values){const b=bucket(date,page.period.aggregation);buckets.set(b,[...(buckets.get(b)??[]),values[index]??0]);}});
    return {key,label,unit:'kcal',dailyAggregation:'sum',points:[...buckets].sort().map(([date,values])=>({date,value:values.reduce((a,b)=>a+b,0)/values.length,recordedDays:values.length,spanDays:page.period.aggregation==='week'?7:page.period.aggregation==='month'?new Date(Date.UTC(Number(date.slice(0,4)),Number(date.slice(5,7)),0)).getUTCDate():1,coveredDays:values.length}))};
  });
}
export function exerciseKinds(page?: HealthPage): Series[] {
  if (!page || page.dataTruncated) return [];
  const kinds=[...new Set(page.records.map(r=>String(r.values.exercise_type??'기타')))];
  const dates=[...new Set(page.records.map(r=>r.date))];
  return kinds.map(kind=>{
    const buckets=new Map<string,number[]>();
    dates.forEach(date=>{const rows=page.records.filter(r=>r.date===date);if(rows.some(r=>numeric(r,'duration_seconds')===null))return;const value=rows.filter(r=>String(r.values.exercise_type??'기타')===kind).reduce((sum,r)=>sum+(numeric(r,'duration_seconds')??0)/60,0);const b=bucket(date,page.period.aggregation);buckets.set(b,[...(buckets.get(b)??[]),value]);});
    return {key:kind,label:kind,unit:'분',dailyAggregation:'sum',points:[...buckets].sort().map(([date,values])=>({date,value:values.reduce((a,b)=>a+b,0)/values.length,recordedDays:values.length,spanDays:values.length,coveredDays:values.length}))};
  });
}
