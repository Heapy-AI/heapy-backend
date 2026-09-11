// 작성자: 김진우 — 내 건강 공개 API 계약. 분석 요청은 Spring 서버에서만 실행한다.
export type Metric = 'bio' | 'activity' | 'exercise' | 'nutrition' | 'water' | 'sleep';
export type Category = 'bio' | 'activity' | 'nutrition' | 'sleep' | 'checkup' | 'overall';
export type PeriodCode = '7d' | '30d' | '90d' | '180d' | '1y';
export type HealthRecord = { recordId: string; date: string; measuredAt: string; source: string; editable: boolean; deletable: boolean; recordVersion: string; values: Record<string, string | number | boolean | null> };
export type Point = { date: string; value: number; recordedDays: number; spanDays: number; coveredDays: number };
export type Series = { key: string; label: string; unit: string; dailyAggregation: string; points: Point[] };
export type HealthPage = { metric: Metric; period: { code: PeriodCode; from: string; to: string; aggregation: 'day' | 'week' | 'month' | 'raw' }; timezone: string; records: HealthRecord[]; series: Series[]; dataTruncated: boolean; nextCursor: string | null };
export type Analysis = { analysisDate: string; category: Category; cutoff: string; expiresAt: string; generatedAt?: string; status: 'unavailable' | 'pending' | 'generating' | 'generated' | 'failed' | 'result_lost' | 'data_insufficient'; report?: { headline?: string; current_state?: string; summary?: string; overall_analysis?: string; actions?: string[]; recommendations?: string[] } };
export type EntryKind = 'sleep' | 'blood_pressure' | 'body_composition' | 'water' | 'blood_glucose';
