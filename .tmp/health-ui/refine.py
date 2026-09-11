from pathlib import Path
p = Path('C:/Users/jinwo/heapy-frontend/src/features/health')
f=p/'healthModel.ts'
s=f.read_text(encoding='utf-8')
s += '''
// 작성자: 김진우 — 구간의 전체 길이와 조회 범위에 포함된 날짜 수를 구분한다.
export function coverage(date: string, period: HealthPage['period']) {
  const start = new Date(date + 'T00:00:00Z');
  const end = new Date(start);
  if (period.aggregation === 'month') end.setUTCMonth(end.getUTCMonth() + 1);
  else end.setUTCDate(end.getUTCDate() + (period.aggregation === 'week' ? 7 : 1));
  const day = 86400000;
  return {
    spanDays: (end.getTime() - start.getTime()) / day,
    coveredDays: Math.max(0, (Math.min(end.getTime(), Date.parse(period.to) + day) - Math.max(start.getTime(), Date.parse(period.from))) / day),
  };
}
// 작성자: 김진우 — 일별 운동·기타 열량을 먼저 분리한 뒤 동일한 기록일 분모로 평균한다.
export function activityCalories(activity?: HealthPage, exercise?: HealthPage): Series[] {
  if (!activity || !exercise || activity.dataTruncated || exercise.dataTruncated) return [];
  const daily = new Map<string, number[]>();
  for (const date of new Set(activity.records.map(r => r.date))) {
    if (date < exercise.period.from || date > exercise.period.to) continue;
    const a = activity.records.filter(r => r.date === date);
    const e = exercise.records.filter(r => r.date === date);
    if (a.some(r => numeric(r, 'active_calories_kcal') === null) || e.some(r => numeric(r, 'calories_kcal') === null)) continue;
    const total = a.reduce((n, r) => n + (numeric(r, 'active_calories_kcal') ?? 0), 0);
    const workout = e.reduce((n, r) => n + (numeric(r, 'calories_kcal') ?? 0), 0);
    daily.set(date, [workout, Math.max(total - workout, 0)]);
  }
  return ['운동', '기타 활동'].map((label, i) => {
    const groups = new Map<string, number[]>();
    daily.forEach((v, date) => {
      const b = bucket(date, activity.period.aggregation);
      groups.set(b, [...(groups.get(b) ?? []), v[i] ?? 0]);
    });
    return { key: 'activity_' + i, label, unit: 'kcal', dailyAggregation: 'sum', points: [...groups].sort().map(([date, values]) => ({date, value: values.reduce((a, b) => a + b, 0) / values.length, recordedDays: values.length, ...coverage(date, activity.period)})) };
  });
}
'''
start=s.index('        spanDays:\n')
end=s.index('\n      })),',start)
s=s[:start]+'        ...coverage(date, page.period),'+s[end:]
s=s.replace('        spanDays: values.length,\n        coveredDays: values.length,','        ...coverage(date, page.period),')
f.write_text(s,encoding='utf-8')
f=p/'HealthChart.tsx';s=f.read_text(encoding='utf-8')
s=s.replace('  note,\n', '  note,\n  tableSeries,\n',1).replace('  note?: string;','  note?: string;\n  tableSeries?: Series[];')
s=s.replace('                    s.points.map(p => {','                    s.points.filter(p => kind !== \'stack\' || series.every(a => a.points.some(v => v.date === p.date))).map(p => {')
s=s.replace('            {series.map(s => {','            {(tableSeries ?? series).map(s => {')
f.write_text(s,encoding='utf-8')
f=p/'HealthScreen.tsx';s=f.read_text(encoding='utf-8')
s=s.replace('  exerciseKinds,','  activityCalories,\n  exerciseKinds,',1)
s=s.replace('title="활동 열량"\n          kind="bar"\n          series={series(data.activity, [\'active_calories_kcal\'])}', 'title="활동 열량"\n          kind="stack"\n          series={activityCalories(data.activity, data.exercise)}')
s=s.replace('note="활동 열량에는 운동이 포함될 수 있어 운동 열량을 다시 더하지 않아요."','note="데모 기준으로 운동과 기타 활동을 나눠요. 기타 활동은 활동 열량에서 운동 열량을 뺀 값이며, 음수는 0으로 표시해요."')
s=s.replace('          note="1잔은 250mL예요."','          tableSeries={series(data.water, [\'amount_ml\'])}\n          note="1잔은 250mL예요. 수치표에서는 mL로 확인해요."')
s=s.replace("{format((numeric(r, 'duration_seconds') ?? 0) / 60)}분", "{format(numeric(r, 'duration_seconds') === null ? null : numeric(r, 'duration_seconds')! / 60)}분")
s=s.replace('<Text style={hs.section}>운동 기록</Text>','<Text style={hs.section}>운동 기록</Text>\n          {(data.exercise?.records.length ?? 0) > 100 && <Text style={hs.muted}>최근 100개 기록을 표시해요. 그래프는 조회 기간 전체 기록으로 계산해요.</Text>}')
f.write_text(s,encoding='utf-8')
