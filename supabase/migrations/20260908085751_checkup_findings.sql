-- 작성자: 김진우
-- 공유 DB 미적용. 기존 검진 결과를 이동하거나 item_code 제약을 변경하지 않는다.
-- 마이그레이션 실행기는 이 파일 전체를 하나의 트랜잭션으로 실행해야 한다.

create function private.valid_checkup_finding(payload jsonb) returns boolean
language plpgsql immutable set search_path = pg_catalog as $$
declare
    field text;
    summary jsonb;
begin
    if jsonb_typeof(payload) is distinct from 'object' then return false; end if;
    if exists (select 1 from jsonb_object_keys(payload) k where k not in
        ('schemaVersion','findingId','classification','examType','examName','text','bodySite','method','performedAt','summary'))
        then return false; end if;
    if payload->'schemaVersion' is distinct from '1'::jsonb then return false; end if;
    foreach field in array array['findingId','classification','examName','text'] loop
        if jsonb_typeof(payload->field) is distinct from 'string' then return false; end if;
    end loop;
    if (payload->>'findingId') !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        or payload->>'classification' not in ('procedure_finding','overall_opinion')
        or length(btrim(payload->>'text')) = 0 or length(payload->>'text') > 12000
        or length(btrim(payload->>'examName')) = 0 or length(payload->>'examName') > 200
        then return false; end if;
    foreach field in array array['bodySite','method'] loop
        if payload ? field and payload->field <> 'null'::jsonb then
            if jsonb_typeof(payload->field) <> 'string' or length(btrim(payload->>field)) = 0
                or length(payload->>field) > 200 then return false; end if;
        end if;
    end loop;
    if payload->>'classification' = 'procedure_finding' then
        if jsonb_typeof(payload->'examType') is distinct from 'string'
            or payload->>'examType' not in ('upper_gi_endoscopy','colonoscopy','biopsy','ultrasound','ct','mri','other_procedure')
            then return false; end if;
    else
        foreach field in array array['examType','bodySite','method','performedAt'] loop
            if payload ? field and payload->field <> 'null'::jsonb then return false; end if;
        end loop;
    end if;
    if payload ? 'performedAt' and payload->'performedAt' <> 'null'::jsonb then
        if jsonb_typeof(payload->'performedAt') <> 'string' or payload->>'performedAt' !~ '^\d{4}-\d{2}-\d{2}$'
            or ((payload->>'performedAt')::date)::text <> payload->>'performedAt' then return false; end if;
    end if;
    summary := payload->'summary';
    if summary is not null and summary <> 'null'::jsonb then
        if jsonb_typeof(summary) <> 'object' then return false; end if;
        if exists (select 1 from jsonb_object_keys(summary) k where k not in ('text','source','basisHash')) then return false; end if;
        foreach field in array array['text','source','basisHash'] loop
            if jsonb_typeof(summary->field) is distinct from 'string' then return false; end if;
        end loop;
        if length(btrim(summary->>'text')) = 0 or length(summary->>'text') > 2000
            or summary->>'source' not in ('institution','ai')
            or summary->>'basisHash' <> encode(sha256(convert_to(payload->>'text','UTF8')), 'hex') then return false; end if;
    end if;
    return octet_length(payload::text) <= 65536;
exception when others then
    return false;
end;
$$;
revoke all on function private.valid_checkup_finding(jsonb) from public, anon, authenticated;
grant execute on function private.valid_checkup_finding(jsonb) to service_role;

create table public.health_checkup_findings (
    record_id uuid not null references public.health_checkup_records(record_id) on delete cascade,
    finding_id uuid not null,
    classification text not null check (classification in ('procedure_finding','overall_opinion')),
    display_order smallint not null check (display_order between 0 and 49),
    content jsonb not null,
    created_at timestamptz not null default now(),
    primary key (record_id, finding_id),
    constraint health_checkup_findings_content_check check (
        private.valid_checkup_finding(content)
        and content->>'findingId' = finding_id::text
        and content->>'classification' = classification)
);
create index health_checkup_findings_record_classification_idx
    on public.health_checkup_findings(record_id, classification, display_order);
alter table public.health_checkup_findings enable row level security;
revoke all on public.health_checkup_findings from public, anon, authenticated;
grant select on public.health_checkup_findings to authenticated;
grant select, insert, update, delete on public.health_checkup_findings to service_role;
create policy health_checkup_findings_owner_select on public.health_checkup_findings
    for select to authenticated using (exists (
        select 1 from public.health_checkup_records r
        where r.record_id = health_checkup_findings.record_id and r.user_id = (select auth.uid())
    ));
comment on table public.health_checkup_findings is '사용자 확정 검사 소견·종합소견. 작성자: 김진우';
comment on column public.health_checkup_findings.content is '허용 스키마의 확정 소견만 보관. 임시 OCR 덤프·원본 경로 금지';

-- 기존 값이 바뀌었거나 사용 데이터가 추가된 환경은 자동 수정하지 않고 점검한다.
do $$
begin
    if not exists (select 1 from public.master_checkup_item where item_code = 'CHEST_XRAY_PA'
        and item_name = '흉부방사선 직접촬영(PA)' and standard_unit is null and value_type = 'numeric' and is_active)
        or exists (select 1 from public.health_checkup_results where item_code = 'CHEST_XRAY_PA') then
        raise exception '흉부 PA 마스터·기존 데이터 사전 점검이 필요합니다';
    end if;
    if exists (select 1 from public.master_checkup_item
        where item_code in ('HEARING_GENERAL_LEFT','HEARING_GENERAL_RIGHT','CHEST_XRAY')) then
        raise exception '신규 코드 충돌을 먼저 점검해 주세요';
    end if;
end;
$$;
update public.master_checkup_item set value_type = 'text', updated_at = now()
    where item_code = 'CHEST_XRAY_PA';
insert into public.master_checkup_item(item_code,item_name,standard_unit,item_category,value_type,display_order,is_active)
values
    ('HEARING_GENERAL_LEFT','일반 청력(좌)',null,'general','text',0,true),
    ('HEARING_GENERAL_RIGHT','일반 청력(우)',null,'general','text',0,true),
    ('CHEST_XRAY','흉부 X선(촬영 방향 미기재)',null,'general','text',0,true);
