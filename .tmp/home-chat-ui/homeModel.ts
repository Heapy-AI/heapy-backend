// 작성자: 김진우 — 홈 미리보기 전용 상태이며 업무 API에 전송하지 않는다.
export const moduleLabels = { briefing: 'AI 건강 브리핑', metrics: '핵심 데이터', medication: '오늘의 복약', mission: '추천 미션', weekly: '주간 변화', checkup: '최근 건강검진' } as const;
export type ModuleId = keyof typeof moduleLabels;
export const metrics = { sleep: ['수면', '7시간 12분', '+42분'], steps: ['걸음 수', '5,920보', '전주 대비 −12%'], exercise: ['운동시간', '42분', '가벼운 운동'], count: ['운동 횟수', '주 3회', '이번 주'], heart: ['심박수', '72 bpm', '안정 시'], pressure: ['혈압', '120/80', 'mmHg'] } as const;
export type MetricId = keyof typeof metrics;
export type HomeSettings = { modules: ModuleId[]; metrics: MetricId[]; medicationMode: 'next' | 'all'; medicationName: boolean; medicationButton: boolean; medicationProgress: boolean; weekly: 'steps' | 'sleep' | 'exercise' };
export function defaultHomeSettings(): HomeSettings { return { modules: ['briefing', 'metrics', 'medication', 'mission'], metrics: ['sleep', 'steps'], medicationMode: 'next', medicationName: true, medicationButton: true, medicationProgress: true, weekly: 'steps' }; }
export function moveItem<T>(items: T[], from: number, to: number): T[] { const result = [...items]; const [item] = result.splice(from, 1); result.splice(Math.max(0, Math.min(to, result.length)), 0, item); return result; }
