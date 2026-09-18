-- 작성자: 김진우 — 삭제 예외를 제거하여 기존 원장 불변 규칙으로 되돌린다. 이미 삭제된 데이터는 복원하지 않는다.
begin;
create or replace function public.reject_coin_ledger_mutation() returns trigger
language plpgsql security invoker set search_path = '' as $$
begin
    raise exception '코인 원장은 수정하거나 삭제할 수 없습니다.';
end;
$$;
revoke all on function public.reject_coin_ledger_mutation() from public, anon, authenticated;
comment on function public.reject_coin_ledger_mutation() is null;
commit;
