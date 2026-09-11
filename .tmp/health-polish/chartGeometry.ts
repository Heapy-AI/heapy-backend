// 작성자: 김진우 — 결측을 수치로 채우지 않고 실측점 사이의 간격만 별도 선분으로 구분한다.
import {Series} from './types';
export function lineSegments(points: Series['points'], dates: string[]) {
  const ordered = points.filter(p => dates.includes(p.date)).slice().sort((a,b)=>a.date.localeCompare(b.date));
  return ordered.slice(1).map((to,index)=>({from: ordered[index]!,to,gap: dates.indexOf(to.date)-dates.indexOf(ordered[index]!.date)>1}));
}
export function axisMaximum(value: number) {
  if (!Number.isFinite(value) || value <= 0) return 1;
  const magnitude = 10 ** Math.floor(Math.log10(value));
  const step = magnitude / 2;
  return Math.ceil(value * 1.08 / step) * step;
}
