-- 작성자: 김진우 — 임시 테이블/함수에서만 검증하고 모두 롤백한다.
begin;
create temporary table withdrawal_coin_test (user_id uuid, amount integer);
insert into withdrawal_coin_test values
('11111111-1111-1111-1111-111111111111', 10),
('22222222-2222-2222-2222-222222222222', 20);
-- 마이그레이션과 같은 함수를 임시 스키마에서 검증한다.
create or replace function pg_temp.reject_coin_ledger_mutation() returns trigger
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
create trigger withdrawal_coin_guard before update or delete on withdrawal_coin_test
for each row execute function pg_temp.reject_coin_ledger_mutation();
do $$
begin
  begin
    delete from withdrawal_coin_test;
    raise exception '일반 삭제가 허용됨' using errcode='Z0001';
  exception when sqlstate 'P0001' then null; end;
  perform set_config('heapy.withdrawal_user', '11111111-1111-1111-1111-111111111111', true);
  perform set_config('heapy.withdrawal_txid', pg_current_xact_id()::text, true);
  begin
    update withdrawal_coin_test set amount=999;
    raise exception '원장 수정이 허용됨' using errcode='Z0001';
  exception when sqlstate 'P0001' then null; end;
  begin
    delete from withdrawal_coin_test where user_id='22222222-2222-2222-2222-222222222222';
    raise exception '다른 사용자 삭제가 허용됨' using errcode='Z0001';
  exception when sqlstate 'P0001' then null; end;
  perform set_config('heapy.withdrawal_txid', '0', true);
  begin
    delete from withdrawal_coin_test where user_id='11111111-1111-1111-1111-111111111111';
    raise exception '다른 트랜잭션 삭제가 허용됨' using errcode='Z0001';
  exception when sqlstate 'P0001' then null; end;
  perform set_config('heapy.withdrawal_user', '', true);
  perform set_config('heapy.withdrawal_txid', '', true);
end $$;
savepoint withdrawal_start;
select set_config('heapy.withdrawal_user', '11111111-1111-1111-1111-111111111111', true),
set_config('heapy.withdrawal_txid', pg_current_xact_id()::text, true);
delete from withdrawal_coin_test where user_id='11111111-1111-1111-1111-111111111111';
do $$ begin
  if (select count(*) from withdrawal_coin_test) <> 1 then
    raise exception '본인 삭제 범위 오류';
  end if;
end $$;
rollback to savepoint withdrawal_start;
do $$ begin
  if (select count(*) from withdrawal_coin_test) <> 2
      or current_setting('heapy.withdrawal_user', true) <> ''
      or current_setting('heapy.withdrawal_txid', true) <> '' then
    raise exception '데이터 또는 삭제 권한 롤백 실패';
  end if;
end $$;
grant select, delete on withdrawal_coin_test to authenticated;
select set_config('heapy.withdrawal_user', '11111111-1111-1111-1111-111111111111', true),
set_config('heapy.withdrawal_txid', pg_current_xact_id()::text, true);
set local role authenticated;
do $$ begin
  begin
    delete from pg_temp.withdrawal_coin_test where user_id='11111111-1111-1111-1111-111111111111';
    raise exception '앱 역할의 우회 삭제 허용됨' using errcode='Z0001';
  exception when sqlstate 'P0001' then null; end;
end $$;
reset role;
select '일반 삭제·수정 차단, 타 사용자·타 트랜잭션 차단, 본인 삭제, 롤백, 앱 역할 차단 통과' as result;
rollback;
