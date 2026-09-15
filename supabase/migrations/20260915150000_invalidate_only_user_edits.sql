-- 작성자: 고수연 — 동기화 보정은 분석을 막지 않는다. 사람이 과거를 고쳤을 때만 막는다.
--
-- 지금은 자정 이전에 만들어진 기록이 오늘 바뀌기만 하면 그 사용자의 오늘 분석이 통째로
-- 막힌다. 그런데 삼성헬스 동기화는 1년치 활동 기록을 매번 다시 쓰고, 걸음 수 같은 값이
-- 보정되어 내려온다. 실제로 한 사용자의 활동 366건 중 364건이 한 번의 동기화로 갱신됐다.
--
-- 그 결과가 정확히 뒤집혀 있었다. 동기화하는 사용자는 점수도 분석도 브리핑도 못 받고,
-- 기록이 하나도 없는 사용자만 받았다.
--
-- 원래 우려는 살린다. 사람이 과거 기록을 고치거나 지웠으면 자정 당시 원본을 확신할 수
-- 없으니 그날은 분석하지 않는다. 다만 기기가 스스로 보정한 것은 값을 더 정확하게 만드는
-- 일이라 막을 이유가 없다.
--
-- 가르는 기준은 sync_run_id 다. 동기화는 행을 쓸 때마다 이 값을 새 실행 id로 바꾸고
-- (HealthSyncService), 사용자 편집은 요청에 담긴 열만 쓰므로 이 값을 건드리지 않는다.
-- health_checkup_records 처럼 이 열이 없는 표도 있어 jsonb 로 꺼낸다. 없으면 양쪽이 다
-- null 이라 '바뀌지 않음'으로 읽혀 예전과 같이 동작한다.
create or replace function private.invalidate_health_analysis() returns trigger
language plpgsql security invoker set search_path = '' as $$
declare analysis_day date := (clock_timestamp() at time zone 'Asia/Seoul')::date;
begin
    if tg_op = 'UPDATE' then
        -- 값이 그대로면 무효화하지 않는다. 기존 완화 장치를 그대로 둔다.
        if (to_jsonb(old) - array['updated_at','source_updated_at','sync_run_id','external_record_id','is_user_override'])
           is not distinct from
           (to_jsonb(new) - array['updated_at','source_updated_at','sync_run_id','external_record_id','is_user_override']) then
            return new;
        end if;
        -- 동기화가 고친 것은 통과시킨다.
        if to_jsonb(new)->>'sync_run_id' is distinct from to_jsonb(old)->>'sync_run_id' then
            return new;
        end if;
    end if;
    if old.created_at < (analysis_day::timestamp at time zone 'Asia/Seoul')
       and exists (select 1 from public.users where user_id=old.user_id) then
        insert into private.health_analysis_invalidations(user_id,analysis_date)
        values(old.user_id,analysis_day) on conflict do nothing;
    end if;
    if tg_op = 'DELETE' then return old; end if;
    return new;
end;
$$;
revoke all on function private.invalidate_health_analysis() from public, anon, authenticated;

comment on function private.invalidate_health_analysis() is
    '고수연: 사람이 과거 기록을 고치거나 지운 날만 분석을 막는다. 동기화 보정(sync_run_id 변경)은 통과시킨다.';

-- 이미 쌓인 오늘치 쪽지를 비운다. 동기화 때문에 걸린 것이라 새 규칙에서는 생기지 않는다.
delete from private.health_analysis_invalidations where analysis_date >= (clock_timestamp() at time zone 'Asia/Seoul')::date;
