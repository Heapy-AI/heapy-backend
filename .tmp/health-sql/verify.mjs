// 작성자: 김진우 — 운영 데이터 없이 신규 마이그레이션의 PostgreSQL 동작을 검증한다.
import { PGlite } from '@electric-sql/pglite';
import { readFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
const db = await PGlite.create();
try {
  await db.exec(`
    create schema private;
    create role anon;
    create role authenticated;
    create table users(user_id uuid primary key);
    create table lifestyle_water_intake(water_intake_id uuid primary key, user_id uuid references users on delete cascade,
      amount_ml numeric not null check(amount_ml>0), consumed_at timestamptz not null, source text,
      is_user_override boolean default false, created_at timestamptz default now(),updated_at timestamptz default now());
    create table lifestyle_bio(bio_id uuid primary key,user_id uuid references users on delete cascade,
      created_at timestamptz default now(),bio_type text,heart_rate_bpm numeric,blood_glucose_mg_dl numeric,
      systolic_mmhg numeric,diastolic_mmhg numeric,weight_kg numeric,bmi_value numeric,
      body_fat_percent numeric,skeletal_muscle_kg numeric,
      constraint lifestyle_bio_value_shape_check check(bio_type is not null));
    create table health_sync_runs(sync_mode text,constraint health_sync_runs_mode_check check(sync_mode in ('app_open','manual_refresh')));
  `);
  for (const table of ['lifestyle_activity','lifestyle_exercise','lifestyle_nutrition','lifestyle_sleep','health_checkup_records']) {
    await db.exec(`create table ${table}(user_id uuid references users on delete cascade,created_at timestamptz default now())`);
  }
  const sql = await readFile('supabase/migrations/20260910020818_health_records_contract.sql', 'utf8');
  await db.exec('begin;' + sql + ';commit;');
  const user = '00000000-0000-0000-0000-000000000001';
  const record = '00000000-0000-0000-0000-000000000002';
  await db.query('insert into users values($1)', [user]);
  await db.query(`insert into lifestyle_water_intake(water_intake_id,user_id,amount_ml,consumed_at,source,created_at)
    values($1,$2,250,now()-interval '2 days','samsung_health',now()-interval '2 days')`, [record,user]);
  await db.query(`insert into private.water_record_origins select water_intake_id,user_id,to_jsonb(w),now() from lifestyle_water_intake w where water_intake_id=$1`,[record]);
  await db.query(`update lifestyle_water_intake set amount_ml=500,source='manual',is_user_override=true where water_intake_id=$1`,[record]);
  assert.equal((await db.query('select count(*)::int n from private.health_analysis_invalidations')).rows[0].n,1);
  await db.exec(`update lifestyle_water_intake w set source='samsung_health',is_user_override=false,
    amount_ml=(o.original_values->>'amount_ml')::numeric from private.water_record_origins o where w.water_intake_id=o.record_id`);
  assert.equal(Number((await db.query('select amount_ml from lifestyle_water_intake')).rows[0].amount_ml),250);
  await db.query(`insert into private.health_analysis_runs(user_id,analysis_date,category,status) values($1,current_date,'bio','generating')`,[user]);
  await db.query(`insert into private.health_analysis_runs(user_id,analysis_date,category,status) values($1,current_date,'bio','generating') on conflict do nothing`,[user]);
  assert.equal((await db.query('select count(*)::int n from private.health_analysis_runs')).rows[0].n,1);
  const grants = await db.query(`select has_table_privilege('authenticated','private.health_analysis_runs','select') allowed`);
  assert.equal(grants.rows[0].allowed,false);
  await db.query('delete from users where user_id=$1',[user]);
  assert.equal((await db.query('select count(*)::int n from private.water_record_origins')).rows[0].n,0);
  assert.equal((await db.query('select count(*)::int n from private.health_analysis_invalidations')).rows[0].n,0);
  console.log('마이그레이션 적용·원본 복원·변경 감지·중복 실행권·권한 차단·계정 연쇄 삭제 검증 통과');
} finally { await db.close(); }
