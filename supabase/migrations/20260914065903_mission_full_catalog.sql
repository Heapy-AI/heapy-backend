-- 작성자: 김진우 — 전체 미션은 기존 테이블을 확장하고 버전 3으로 활성화한다.
alter table public.users add column mission_options jsonb not null default '{}';
alter table public.users add constraint mission_options_object check(jsonb_typeof(mission_options)='object');
alter table public.mission_feedback alter column user_mission_id drop not null;
alter table public.mission_feedback add column mission_code text,add column event_key uuid;
do $$ declare c record; begin
 for c in select conname from pg_constraint where conrelid='public.mission_feedback'::regclass and contype='c' and pg_get_constraintdef(oid) like '%event_type%' loop
  execute format('alter table public.mission_feedback drop constraint %I',c.conname);
 end loop;
end $$;
alter table public.mission_feedback add constraint mission_feedback_event_type_check check(event_type in ('completed','failed','abandoned','rejected','viewed'));
alter table public.mission_feedback add constraint mission_feedback_identity check(user_mission_id is not null or mission_code is not null);
create unique index mission_feedback_request_key on public.mission_feedback(user_id,event_key) where event_key is not null;
create index mission_feedback_user_time on public.mission_feedback(user_id,created_at desc);
-- 21개는 기존 음식·혈당 보류 정책을 유지하고, 나머지 38개를 모두 실행 가능하게 등록한다.
insert into public.mission_catalog(mission_code,version,mission_type,title,description,is_active)
select mission_code,3,mission_type,title,description,mission_code not like 'NUT-%' and mission_code not like 'REC-GLU-%'
from public.mission_catalog where version=2 on conflict(mission_code,version) do nothing;
insert into public.mission_rules(mission_code,version,required_data,condition,exclusions,completion_rule)
select mission_code,3,required_data,condition,exclusions,completion_rule from public.mission_rules where version=2 on conflict(mission_code,version) do nothing;
update public.mission_rules set condition='{"ruleId": "SLEEP_BED_LATE_01", "policy": "HEAPY-v0.2", "status": "active"}'::jsonb,completion_rule='{"metric": "bedtime", "manualAllowed": false}'::jsonb,required_data='["bedtime"]'::jsonb where mission_code='SLP-001' and version=3;
update public.mission_rules set condition='{"ruleId": "SLEEP_WAKE_IRREGULAR_01", "policy": "HEAPY-v0.2", "status": "active"}'::jsonb,completion_rule='{"metric": "wake", "manualAllowed": false}'::jsonb,required_data='["wake"]'::jsonb where mission_code='SLP-002' and version=3;
update public.mission_rules set condition='{"ruleId": "SLEEP_BED_IRREGULAR_01", "policy": "HEAPY-v0.2", "status": "active"}'::jsonb,completion_rule='{"metric": "bed_prepare", "manualAllowed": true}'::jsonb,required_data='["bed_prepare"]'::jsonb where mission_code='SLP-004' and version=3;
update public.mission_rules set condition='{"ruleId": "SLEEP_BED_IRREGULAR_01", "policy": "HEAPY-v0.2", "status": "active"}'::jsonb,completion_rule='{"metric": "weekly_bed", "manualAllowed": false}'::jsonb,required_data='["weekly_bed"]'::jsonb where mission_code='SLP-005' and version=3;
update public.mission_rules set condition='{"ruleId": "SLEEP_WAKE_IRREGULAR_01", "policy": "HEAPY-v0.2", "status": "active"}'::jsonb,completion_rule='{"metric": "weekly_wake", "manualAllowed": false}'::jsonb,required_data='["weekly_wake"]'::jsonb where mission_code='SLP-006' and version=3;
update public.mission_rules set condition='{"ruleId": "SLEEP_SHORT_01", "policy": "HEAPY-v0.2", "status": "active"}'::jsonb,completion_rule='{"metric": "weekly_sleep", "manualAllowed": false}'::jsonb,required_data='["weekly_sleep"]'::jsonb where mission_code='SLP-007' and version=3;
update public.mission_rules set condition='{"ruleId": "SLEEP_BED_LATE_01", "policy": "HEAPY-v0.2", "status": "active"}'::jsonb,completion_rule='{"metric": "bedtime", "manualAllowed": false}'::jsonb,required_data='["bedtime"]'::jsonb where mission_code='SLP-008' and version=3;
update public.mission_rules set condition='{"ruleId": "SLEEP_WEEKEND_SHIFT_01", "policy": "HEAPY-v0.2", "status": "active"}'::jsonb,completion_rule='{"metric": "weekend_wake", "manualAllowed": false}'::jsonb,required_data='["weekend_wake"]'::jsonb where mission_code='SLP-009' and version=3;
update public.mission_rules set condition='{"ruleId": "ACT_MEAL_WALK_LUNCH_01", "policy": "HEAPY-v0.2", "status": "active"}'::jsonb,completion_rule='{"metric": "meal_walk", "manualAllowed": true}'::jsonb,required_data='["meal_walk"]'::jsonb where mission_code='ACT-001' and version=3;
update public.mission_rules set condition='{"ruleId": "ACT_MEAL_WALK_DINNER_01", "policy": "HEAPY-v0.2", "status": "active"}'::jsonb,completion_rule='{"metric": "meal_walk", "manualAllowed": true}'::jsonb,required_data='["meal_walk"]'::jsonb where mission_code='ACT-002' and version=3;
update public.mission_rules set condition='{"ruleId": "ACT_WEEKLY_MODERATE_01", "policy": "HEAPY-v0.2", "status": "active"}'::jsonb,completion_rule='{"metric": "weekly_moderate", "manualAllowed": false}'::jsonb,required_data='["weekly_moderate"]'::jsonb where mission_code='ACT-007' and version=3;
update public.mission_rules set condition='{"ruleId": "ACT_WEEKLY_MODERATE_01", "policy": "HEAPY-v0.2", "status": "active"}'::jsonb,completion_rule='{"metric": "weekly_walk", "manualAllowed": false}'::jsonb,required_data='["weekly_walk"]'::jsonb where mission_code='ACT-008' and version=3;
update public.mission_rules set condition='{"ruleId": "ACT_STRENGTH_LOW_01", "policy": "HEAPY-v0.2", "status": "active"}'::jsonb,completion_rule='{"metric": "weekly_strength", "manualAllowed": true}'::jsonb,required_data='["weekly_strength"]'::jsonb where mission_code='ACT-009' and version=3;
update public.mission_rules set condition='{"ruleId": "ACT_DISTANCE_DROP_01", "policy": "HEAPY-v0.2", "status": "active"}'::jsonb,completion_rule='{"metric": "distance", "manualAllowed": false}'::jsonb,required_data='["distance"]'::jsonb where mission_code='ACT-011' and version=3;
update public.mission_rules set condition='{"ruleId": "WATER_LOW_01", "policy": "HEAPY-v0.2", "status": "active"}'::jsonb,completion_rule='{"metric": "water", "manualAllowed": false}'::jsonb,required_data='["water"]'::jsonb where mission_code='WTR-001' and version=3;
update public.mission_rules set condition='{"ruleId": "WATER_LOW_OR_DROP_01", "policy": "HEAPY-v0.2", "status": "active"}'::jsonb,completion_rule='{"metric": "water_before_time", "manualAllowed": false}'::jsonb,required_data='["water_before_time"]'::jsonb where mission_code='WTR-002' and version=3;
update public.mission_rules set condition='{"ruleId": "WATER_DROP_01", "policy": "HEAPY-v0.2", "status": "active"}'::jsonb,completion_rule='{"metric": "water_before_meal", "manualAllowed": false}'::jsonb,required_data='["water_before_meal"]'::jsonb where mission_code='WTR-003' and version=3;
update public.mission_rules set condition='{"ruleId": "WATER_LOW_OR_DROP_01", "policy": "HEAPY-v0.2", "status": "active"}'::jsonb,completion_rule='{"metric": "water_before_time", "manualAllowed": false}'::jsonb,required_data='["water_before_time"]'::jsonb where mission_code='WTR-004' and version=3;
update public.mission_rules set condition='{"ruleId": "WATER_DROP_01", "policy": "HEAPY-v0.2", "status": "active"}'::jsonb,completion_rule='{"metric": "water_before_meal", "manualAllowed": false}'::jsonb,required_data='["water_before_meal"]'::jsonb where mission_code='WTR-005' and version=3;
update public.mission_rules set condition='{"ruleId": "BIO_DATA_BP_AM_01", "policy": "HEAPY-v0.2", "status": "active"}'::jsonb,completion_rule='{"metric": "bp_am", "manualAllowed": false}'::jsonb,required_data='["bp_am"]'::jsonb where mission_code='REC-BIO-001' and version=3;
update public.mission_rules set condition='{"ruleId": "BIO_DATA_BP_PM_01", "policy": "HEAPY-v0.2", "status": "active"}'::jsonb,completion_rule='{"metric": "bp_pm", "manualAllowed": false}'::jsonb,required_data='["bp_pm"]'::jsonb where mission_code='REC-BIO-002' and version=3;
update public.mission_rules set condition='{"ruleId": "BIO_DATA_BP_WEEK_01", "policy": "HEAPY-v0.2", "status": "active"}'::jsonb,completion_rule='{"metric": "bp_week", "manualAllowed": false}'::jsonb,required_data='["bp_week"]'::jsonb where mission_code='REC-BIO-003' and version=3;
update public.mission_rules set condition='{"ruleId": "BIO_DATA_WEIGHT_01", "policy": "HEAPY-v0.2", "status": "active"}'::jsonb,completion_rule='{"metric": "record_weight", "manualAllowed": false}'::jsonb,required_data='["record_weight"]'::jsonb where mission_code='REC-BIO-004' and version=3;
update public.mission_rules set condition='{"ruleId": "SLEEP_DATA_01", "policy": "HEAPY-v0.2", "status": "active"}'::jsonb,completion_rule='{"metric": "record_sleep", "manualAllowed": false}'::jsonb,required_data='["record_sleep"]'::jsonb where mission_code='REC-SLP-001' and version=3;
update public.mission_rules set condition='{"ruleId": "CHK_DATA_LATEST_01", "policy": "HEAPY-v0.2", "status": "active"}'::jsonb,completion_rule='{"metric": "record_checkup", "manualAllowed": false}'::jsonb,required_data='["record_checkup"]'::jsonb where mission_code='REC-CHK-001' and version=3;
update public.mission_rules set condition='{"ruleId": "CHK_DATA_HISTORY_01", "policy": "HEAPY-v0.2", "status": "active"}'::jsonb,completion_rule='{"metric": "record_old_checkup", "manualAllowed": false}'::jsonb,required_data='["record_old_checkup"]'::jsonb where mission_code='REC-CHK-002' and version=3;
-- 사용자 결정: SDK에 강도·안정 상태가 없어 ACT-007/008 및 BIO_HR_SLEEP_01은 제외한다.
update public.mission_catalog set is_active=false where version=3 and mission_code in ('ACT-007','ACT-008');
