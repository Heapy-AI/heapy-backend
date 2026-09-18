-- 작성자: 김진우 — 일반 원장 불변성을 유지하고 서버의 본인 탈퇴 트랜잭션만 삭제를 허용한다.
create or replace function public.reject_coin_ledger_mutation() returns trigger
language plpgsql security invoker set search_path = '' as $$
begin
    if tg_op = 'DELETE'
       and current_user = 'postgres'
       and current_setting('heapy.withdrawal_user', true) = old.user_id::text
       and current_setting('heapy.withdrawal_txid', true) = pg_current_xact_id()::text then
        return old;
    end if;
    raise exception '코인 원장은 수정하거나 삭제할 수 없습니다.' using errcode = 'P0001';
end;
$$;
revoke all on function public.reject_coin_ledger_mutation() from public, anon, authenticated;
comment on function public.reject_coin_ledger_mutation() is
    '일반 원장 수정·삭제 금지. postgres의 현재 탈퇴 트랜잭션에서 지정한 사용자 삭제만 허용. 작성자: 김진우';
