-- 작성자: 김진우 — 미션당 10코인 및 계정별 코디 상점. 기존 건강 데이터는 변경하지 않는다.
create table public.shop_items (
    item_id text primary key,
    name text not null,
    slot text not null check (slot in ('hat','top','bag','neck')),
    price integer not null check (price > 0),
    active boolean not null default true,
    display_order integer not null,
    asset_key text not null
);
create table public.shop_purchases (
    purchase_id uuid primary key default gen_random_uuid(),
    user_id uuid not null references public.users(user_id),
    item_id text not null references public.shop_items(item_id),
    idempotency_key uuid not null,
    purchased_at timestamptz not null default now(),
    cancel_until timestamptz not null default now() + interval '7 days',
    cancelled_at timestamptz,
    unique(user_id,idempotency_key),
    unique(purchase_id,user_id,item_id),
    check(cancel_until >= purchased_at),
    check(cancelled_at is null or cancelled_at >= purchased_at)
);
create index shop_purchases_user_time_idx on public.shop_purchases(user_id,purchased_at desc);
create index shop_purchases_item_idx on public.shop_purchases(item_id);
create table public.user_inventory (
    user_id uuid not null references public.users(user_id),
    item_id text not null references public.shop_items(item_id),
    purchase_id uuid not null unique,
    primary key(user_id,item_id),
    foreign key(purchase_id,user_id,item_id) references public.shop_purchases(purchase_id,user_id,item_id)
);
create index user_inventory_item_idx on public.user_inventory(item_id);
create table public.equipped_items (
    user_id uuid primary key references public.users(user_id),
    item_id text not null,
    foreign key(user_id,item_id) references public.user_inventory(user_id,item_id)
);
create table public.coin_ledger (
    entry_id uuid primary key default gen_random_uuid(),
    user_id uuid not null references public.users(user_id),
    amount integer not null,
    kind text not null check(kind in ('mission_reward','purchase','refund')),
    mission_id uuid references public.user_missions(mission_id),
    purchase_id uuid references public.shop_purchases(purchase_id),
    created_at timestamptz not null default now(),
    check ((kind='mission_reward' and amount=10 and mission_id is not null and purchase_id is null)
        or (kind='purchase' and amount<0 and purchase_id is not null and mission_id is null)
        or (kind='refund' and amount>0 and purchase_id is not null and mission_id is null)),
    unique(mission_id),
    unique(purchase_id,kind)
);
create index coin_ledger_user_time_idx on public.coin_ledger(user_id,created_at desc);

-- 불변 원장은 수정·삭제 대신 환불 거래를 추가한다.
create function public.reject_coin_ledger_mutation() returns trigger
language plpgsql set search_path = '' as $$
begin raise exception '코인 원장은 수정하거나 삭제할 수 없습니다.'; end;
$$;
revoke all on function public.reject_coin_ledger_mutation() from public,anon,authenticated;
create trigger coin_ledger_immutable before update or delete on public.coin_ledger
for each row execute function public.reject_coin_ledger_mutation();

insert into public.shop_items(item_id,name,slot,price,display_order,asset_key) values
('blue-cap','파란 캡','hat',90,1,'blue-cap'),
('yellow-backpack','노란 백팩','bag',150,2,'yellow-backpack'),
('purple-hoodie','보라 후디','top',180,3,'purple-hoodie'),
('orange-scarf','오렌지 머플러','neck',70,4,'orange-scarf');

alter table public.shop_items enable row level security;
alter table public.shop_purchases enable row level security;
alter table public.user_inventory enable row level security;
alter table public.equipped_items enable row level security;
alter table public.coin_ledger enable row level security;
revoke all on public.shop_items,public.shop_purchases,public.user_inventory,public.equipped_items,public.coin_ledger from public,anon,authenticated,service_role;
grant select on public.shop_items to service_role;
grant select,insert,update on public.shop_purchases to service_role;
grant select,insert,delete on public.user_inventory,public.equipped_items to service_role;
grant update on public.equipped_items to service_role;
grant select,insert on public.coin_ledger to service_role;
comment on table public.coin_ledger is '미션 완료 10코인·구매 차감·취소 환불 불변 원장. 잔액은 합계로 산출. 작성자: 김진우';
comment on table public.equipped_items is '데모 이미지 방식에 맞춰 사용자당 의상 하나만 착용. 작성자: 김진우';
