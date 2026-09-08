"""공유 DB 읽기 전용 점검과 로컬 실행기 검증. 작성자: 김진우."""

import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
from datetime import datetime
from urllib.parse import urlsplit, parse_qs

ROOT = Path(__file__).resolve().parents[2]
PSQL = ROOT / 'build/tools/postgres-checkup/runtime/pgsql/bin/psql.exe'


def local_values():
    values = {}
    for line in (ROOT / '.env').read_text(encoding='utf-8-sig').splitlines():
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        key, sep, value = line.partition('=')
        if sep:
            values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def target_env():
    values = local_values()
    spec = importlib.util.spec_from_file_location('target', ROOT / 'scripts/deploy/inspect_database_target.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    check = module.inspect(values, 'panaspvsdszeapkoltna')
    if not check['databaseProjectMatches'] or not check['authProjectMatches'] or check['databaseRole'] != 'postgres':
        raise ValueError('대상 불일치')
    url = urlsplit(values['DATABASE_URL'].removeprefix('jdbc:'))
    query = parse_qs(url.query)
    if url.username or url.password or any(k.lower() in {'user', 'password'} for k in query):
        raise ValueError('URL 내부 자격증명은 지원하지 않습니다')
    env = os.environ.copy()
    env.update(PGHOST=url.hostname, PGPORT=str(url.port or 5432), PGDATABASE=url.path.lstrip('/'),
               PGUSER=values['DATABASE_USERNAME'], PGPASSWORD=values['DATABASE_PASSWORD'],
               PGSSLMODE='require', PGCONNECT_TIMEOUT='10', PGAPPNAME='heapy-findings-preflight')
    return env


def sql(env, query):
    result = subprocess.run([str(PSQL), '-X', '-v', 'ON_ERROR_STOP=1', '-At', '-f', '-'],
                            input=query, env=dict(env, PGCLIENTENCODING='UTF8'),
                            capture_output=True, text=True, encoding='utf-8', timeout=75)
    if result.returncode:
        if env.get('PGHOST') == '127.0.0.1' and env.get('PGDATABASE', '').startswith('heapy_findings_cli_'):
            print(result.stderr)
        raise RuntimeError('SQL 실행 실패: 원문 비공개')
    return result.stdout.strip()


def migration_transaction(body, failure=False):
    """검토된 한 건의 변경과 이력을 명시적 트랜잭션으로 묶는다."""
    delimiter = '$heapy_findings_migration$'
    if delimiter in body:
        raise ValueError('이력 인용 구분자 충돌')
    return ('begin;\n' + body + '\n'
        + "insert into supabase_migrations.schema_migrations(version,name,statements) values "
        + "('20260908085751','checkup_findings',ARRAY[" + delimiter + body + delimiter + "]);\n"
        + ('select 1/0;\n' if failure else '') + 'commit;\n')


def inspect_target():
    env = target_env()
    value = sql(env, """select json_build_object('sessionRole',session_user,'effectiveRole',current_user,
        'database',current_database(),'privateUsage',has_schema_privilege(current_user,'private','USAGE'),
        'privateCreate',has_schema_privilege(current_user,'private','CREATE'),
        'resultsInsert',has_table_privilege(current_user,'public.health_checkup_results','INSERT'),
        'historyInsert',has_table_privilege(current_user,'supabase_migrations.schema_migrations','INSERT'))""")
    print(value)


def apply_verified():
    """사용자가 승인한 원본 한 건만 적용하며 불명확한 응답은 재시도하지 않는다."""
    env = target_env()
    state = json.loads(sql(env, """select json_build_object(
        'role',current_user,'session',session_user,
        'tableExists',to_regclass('public.health_checkup_findings') is not null,
        'functionExists',to_regprocedure('private.valid_checkup_finding(jsonb)') is not null,
        'newCodes',(select count(*) from public.master_checkup_item where item_code in
            ('HEARING_GENERAL_LEFT','HEARING_GENERAL_RIGHT','CHEST_XRAY')),
        'paResults',(select count(*) from public.health_checkup_results where item_code='CHEST_XRAY_PA'),
        'history',(select count(*) from supabase_migrations.schema_migrations where version='20260908085751'))"""))
    expected = dict(role='postgres', session='postgres', tableExists=False, functionExists=False,
                    newCodes=0, paResults=0, history=0)
    if state != expected:
        print(json.dumps(state))
        raise RuntimeError('사전 조건 불일치')
    spec = importlib.util.spec_from_file_location('prepare', ROOT / 'scripts/database/prepare_findings_migration.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    body = module.prepare(ROOT / 'supabase/migrations/20260908085751_checkup_findings.sql', module.EXPECTED_SHA256, True)
    original = sql(env, "select row_to_json(m) from public.master_checkup_item m where item_code='CHEST_XRAY_PA'")
    # 마스터 정의만 보존하며 건강 결과·원문·자격증명은 포함하지 않는다.
    evidence = ROOT / 'docs/database/검진_버전2_적용직전_PA_정의.md'
    with evidence.open('x', encoding='utf-8') as stream:
        stream.write('# 적용 직전 PA 마스터 정의\n\n- 작성자: 김진우\n\n```json\n' + original + '\n```\n')
    result = subprocess.run([str(PSQL), '-X', '-v', 'ON_ERROR_STOP=1', '-v', 'VERBOSITY=sqlstate', '-f', '-'],
        input=migration_transaction(body), env=dict(env, PGCLIENTENCODING='UTF8'),
        capture_output=True, text=True, encoding='utf-8', timeout=180)
    if result.returncode:
        raise RuntimeError('적용 실패 또는 상태 불명확. 재시도 전에 원격 확인 필요')
    print('공유 DB 단일 마이그레이션 트랜잭션 완료. 후속 상태 조회가 필요합니다.')


def rehearse():
    env = os.environ.copy()
    env.update(PGHOST='127.0.0.1', PGPORT='55439', PGUSER='postgres', PGPASSWORD='postgres',
               PGDATABASE='postgres', PGSSLMODE='disable', PGCONNECT_TIMEOUT='5')
    spec = importlib.util.spec_from_file_location('prepare', ROOT / 'scripts/database/prepare_findings_migration.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    body = module.prepare(ROOT / 'supabase/migrations/20260908085751_checkup_findings.sql', module.EXPECTED_SHA256, True)
    for failure in (True, False):
        name = ('heapy_findings_cli_failure_' if failure else 'heapy_findings_cli_success_') + datetime.now().strftime('%H%M%S')
        if sql(env, f"select count(*) from pg_database where datname='{name}'") != '0':
            raise RuntimeError('기존 검증 DB가 있어 중단합니다')
        sql(env, f'create database {name}')
        current = dict(env, PGDATABASE=name)
        sql(current, """create schema private; create schema auth;
            do $$ begin
            if not exists(select from pg_roles where rolname='anon') then create role anon; end if;
            if not exists(select from pg_roles where rolname='authenticated') then create role authenticated; end if;
            if not exists(select from pg_roles where rolname='service_role') then create role service_role bypassrls; end if;
            end $$;
            create function auth.uid() returns uuid language sql as $$ select null::uuid $$;
            create table public.health_checkup_records(record_id uuid primary key,user_id uuid);
            create table public.master_checkup_item(item_code text primary key,item_name text,standard_unit text,
                item_category text,value_type text,display_order smallint,is_active boolean,updated_at timestamptz default now());
            create table public.health_checkup_results(result_id uuid primary key,record_id uuid references public.health_checkup_records,
                item_code text references public.master_checkup_item);
            insert into public.master_checkup_item(item_code,item_name,value_type,is_active)
                values('CHEST_XRAY_PA','흉부방사선 직접촬영(PA)','numeric',true);
            create schema supabase_migrations;
            create table supabase_migrations.schema_migrations(version text primary key,name text,statements text[]);
            """)
        work = ROOT / 'build/findings-cli-rehearsal' / name
        migrations = work / 'supabase/migrations'
        migrations.mkdir(parents=True, exist_ok=True)
        (work / 'supabase/config.toml').write_text('project_id = "findings-rehearsal"\n', encoding='utf-8')
        (migrations / '20260908085751_checkup_findings.sql').write_text(
            body + ('\nselect 1/0;\n' if failure else ''), encoding='utf-8')
        result = subprocess.run([str(PSQL), '-X', '-v', 'ON_ERROR_STOP=1', '-v', 'VERBOSITY=sqlstate', '-f', '-'],
            input=migration_transaction(body, failure), env=dict(current, PGCLIENTENCODING='UTF8'),
            capture_output=True, text=True, encoding='utf-8', timeout=120)
        checks = json.loads(sql(current, """select json_build_object(
            'tableExists',to_regclass('public.health_checkup_findings') is not null,
            'newCodes',(select count(*) from public.master_checkup_item where item_code<>'CHEST_XRAY_PA'),
            'paType',(select value_type from public.master_checkup_item where item_code='CHEST_XRAY_PA'),
            'history',(select count(*) from supabase_migrations.schema_migrations))"""))
        expected = {'tableExists':not failure,'newCodes':0 if failure else 3,
                    'paType':'numeric' if failure else 'text','history':0 if failure else 1}
        if checks != expected or (result.returncode == 0) == failure:
            print(json.dumps({'scenario':name,'exit':result.returncode,'checks':checks}))
            print(result.stdout)
            print(result.stderr)
            raise RuntimeError('CLI 검증 실패: 실행 원문 비공개')
        if failure and '22012' not in result.stderr + result.stdout and 'division by zero' not in result.stderr + result.stdout:
            print(result.stdout)
            print(result.stderr)
            raise RuntimeError('의도한 실패에 도달하지 않았습니다')
        print(json.dumps({'scenario':name,'passed':True,'checks':checks}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['inspect-target','rehearse','apply-verified'])
    args = parser.parse_args()
    try:
        {'inspect-target':inspect_target, 'rehearse':rehearse, 'apply-verified':apply_verified}[args.mode]()
    except Exception:
        raise SystemExit('검증 실패. 자격증명과 연결 오류 원문은 출력하지 않습니다.') from None
