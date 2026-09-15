-- 작성자: 고수연 — 분석 문장의 원본을 남긴다.
--
-- 지금 문장은 Redis(HealthAnalysisCache)에만 있고 다음 한국 자정에 만료된다. 그래서 두 가지
-- 일이 생긴다. 하나, 어제 브리핑을 볼 수 없어 지난 흐름을 보여주는 화면을 만들 수 없다.
-- 둘, 재시작·장애·TTL 만료로 Redis가 비면 health_analysis_runs 에는 'generated' 로 남아
-- 있는데 내용은 없는 상태가 된다.
--
-- 새 표를 만들지 않는다. 실행권 확보, 중복 방지, 무효화(health_analysis_invalidations),
-- 10분 타임아웃, 6일 지난 행 정리가 모두 이 표에 붙어 있다. 새 표로 옮기면 그 로직을 한 벌
-- 더 짜야 하고 두 표의 상태가 어긋날 자리가 생긴다.
--
-- category CHECK 은 건드리지 않는다. 홈 화면의 '오늘의 AI 건강 브리핑'은 하루 한 건이라
-- 알갱이가 달라 public.daily_health_briefings 로 따로 간다.
alter table private.health_analysis_runs
    add column result jsonb;

-- 'generated' 인데 result 가 비어 있으면 안 된다는 제약은 걸지 않는다. 이미 저장된 행들이
-- 모두 그 상태라 즉시 위반이 된다. 조회 쪽에서 비어 있으면 'result_lost' 로 답한다.
comment on column private.health_analysis_runs.result is
    '고수연: LLM이 만든 분석 문장의 원본. Redis는 앞단 캐시로만 쓴다. 크기 한도는 캐시와 같게 65,536바이트로 본다.';
