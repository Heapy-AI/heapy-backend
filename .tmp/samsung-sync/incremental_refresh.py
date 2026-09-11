from pathlib import Path

root = Path('C:/Users/jinwo/heapy-frontend')
p = root / 'src/features/dataConnection/samsungSync.ts'
s = p.read_text(encoding='utf-8')
s = s.replace("  if (!options.permission && !(await healthSyncApi.connections(auth)).length)", "  const connections = options.permission ? [] : await healthSyncApi.connections(auth);\n  if (!options.permission && !connections.length)")
s = s.replace("!(await healthSyncApi.connections(auth)).some(", "!connections.some(")
s = s.replace("      // 작성자: 김진우 — 변경 이력이 없는 일별 집계는 최근 1년을 다시 읽어 과거 수정·삭제도 반영한다.\n      for (let from = fromDay; from <= toDay; from = shiftDay(from, 30)) {", "      // 작성자: 김진우 — 최초에는 365일, 이후에는 완료일의 전날부터 읽어 당일 누적값과 늦게 들어온 기록을 반영한다.\n      const cursor = state.cursorState.activity;\n      const recentStart = cursor ? shiftDay(day(Math.min(Date.parse(cursor), cutoffMs)), -1) : fromDay;\n      const activityStart = recentStart > fromDay ? recentStart : fromDay;\n      for (let from = activityStart; from <= toDay; from = shiftDay(from, 30)) {")
p.write_text(s, encoding='utf-8')
p = root / 'src/features/health/HealthScreen.tsx'
s = p.read_text(encoding='utf-8')
start = "  const isCheckup =\n    (tab === 'checkup' && route === 'home') ||\n    route === 'compare' ||\n    route === 'all';\n"
assert start in s
s = s.replace(start, '')
s = s.replace('  // 작성자: 김진우 — 실제 동기화가 완료된 뒤 서버 기록을 다시 조회한다.', start + '  // 작성자: 김진우 — 실제 동기화가 완료된 뒤 서버 기록을 다시 조회한다.')
s = s.replace('    if (refreshing) return;', '    if (refreshing || isCheckup) return;')
s = s.replace('    if (!active) return;\n    let mounted = true;', '    if (!active || isCheckup || entry || route === \'entries\') return;\n    let mounted = true;')
s = s.replace('  }, [active, client]);', '  }, [active, client, isCheckup, entry, route]);')
s = s.replace('refreshControl={\n          <RefreshControl', 'refreshControl={isCheckup ? undefined : (\n          <RefreshControl')
s = s.replace('            tintColor="#20BA8A"\n          />\n        }', '            tintColor="#20BA8A"\n          />\n        )}')
s = s.replace('물 섭취 기록 관리 ›', '물 섭취 기록 추가 ›').replace('앱에서 추가한 과거 기록도 편집·삭제할 수 있어요.', '섭취한 물의 양을 새 기록으로 추가해요.')
p.write_text(s, encoding='utf-8')
p = root / 'src/features/health/HealthEntry.tsx'
s = p.read_text(encoding='utf-8')
s = s.replace("import { ConfirmModal } from '../../shared/components/ConfirmModal';\n", '')
s = s[:s.index('function AmountWheel(')] + '''// 작성자: 김진우 — 직접 입력은 새 기록 추가만 제공하며 기존 기록을 편집하거나 삭제하지 않는다.
function WaterRecords({ onBack }: { onBack: () => void }) {
  const client = useQueryClient();
  const [day, setDay] = useState(koreanDay());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const request = useRef<{ body: string; key: string; payload: Record<string, unknown> } | null>(null);
  const query = useQuery({
    queryKey: ['health', 'water', 'records', day],
    queryFn: async ({ signal }) => {
      let cursor: string | undefined;
      const rows: HealthRecord[] = [];
      do {
        const page = await healthApi.page('water', '7d', signal, {
          baseDate: day, aggregation: 'raw', limit: 200,
          ...(cursor ? { cursor } : {}),
        });
        rows.push(...page.records.filter(r => r.date === day));
        cursor = page.nextCursor ?? undefined;
        if (page.records.some(r => r.date < day)) break;
      } while (cursor);
      return rows;
    },
    retry: false,
  });
  const rows = query.data ?? [];
  const total = rows.reduce((a, r) => a + (numeric(r, 'amount_ml') ?? 0), 0);
  async function add(amountMl: number) {
    if (busy) return;
    setError('');
    try {
      toInstant(day, koreanTime(new Date().toISOString()));
      const consumedAt = new Date(new Date(day + 'T00:00:00+09:00').getTime() + (Date.now() - new Date(koreanDay() + 'T00:00:00+09:00').getTime())).toISOString();
      const body = JSON.stringify({ day, amountMl });
      if (request.current?.body !== body) request.current = {
        body, key: createIdempotencyKey(), payload: { consumedAt, amountMl },
      };
      setBusy(true);
      await healthApi.create('water', request.current.payload, request.current.key);
      request.current = null;
      await client.invalidateQueries({ queryKey: ['health'] });
    } catch (e) {
      setError(e instanceof Error ? e.message : '기록을 추가하지 못했어요.');
    } finally {
      setBusy(false);
    }
  }
  return (
    <ScrollView keyboardShouldPersistTaps="handled" contentContainerStyle={hs.content}>
      <View style={hs.row}>
        <Pressable accessibilityRole="button" accessibilityLabel="물 기록 뒤로" style={hs.back} disabled={busy} onPress={onBack}>
          <Text style={hs.backText}>‹</Text>
        </Pressable>
        <Text style={hs.title}>물 섭취 기록</Text>
      </View>
      <TextInput accessibilityLabel="물 조회 날짜" style={hs.input} value={day} onChangeText={setDay} editable={!busy} />
      <View style={[hs.card, { backgroundColor: '#FFF0E2' }]}>
        <Text style={hs.text}>{day} 총 섭취량</Text>
        <Text style={[hs.value, { color: '#F07343' }]}>{format(total)} mL</Text>
      </View>
      <Text style={hs.section}>빠르게 추가</Text>
      <View style={hs.row}>
        {[125, 250, 500].map((n, i) => (
          <Pressable accessibilityRole="button" disabled={busy} key={n} onPress={() => add(n)} style={[hs.pill, hs.spacer]}>
            <Text style={hs.pillText}>{[0.5, 1, 2][i]}잔 ({n}ml)</Text>
          </Pressable>
        ))}
      </View>
      <Text style={hs.section}>섭취 기록 {rows.length}회</Text>
      {query.isPending && <Text style={hs.muted}>기록을 불러오고 있어요.</Text>}
      {query.isError && <Text accessibilityRole="alert" style={hs.error}>기록을 불러오지 못했어요.</Text>}
      {!query.isPending && !query.isError && !rows.length && <Text style={hs.muted}>아직 기록이 없어요.</Text>}
      {rows.map(r => (
        <View key={r.recordId} style={[hs.card, hs.between]}>
          <View>
            <Text style={hs.text}>섭취량</Text>
            <Text style={hs.muted}>{koreanTime(r.measuredAt)} · {r.source === 'manual' ? '앱 입력' : '삼성헬스'}</Text>
          </View>
          <Text style={hs.value}>{format(numeric(r, 'amount_ml'))} <Text style={hs.muted}>ml</Text></Text>
        </View>
      ))}
      <PrimaryButton label="입력 완료" disabled={busy} onPress={onBack} />
      {!!error && <Text accessibilityRole="alert" style={hs.error}>{error}</Text>}
    </ScrollView>
  );
}
'''
p.write_text(s, encoding='utf-8')
p = root / '__tests__/samsungSync.test.ts'
s = p.read_text(encoding='utf-8')
s += '''
test.each([
  ['2026-08-01T09:00:00Z', '2026-07-31', 1],
  ['2026-07-01T09:00:00Z', '2026-06-30', 2],
  ['2024-01-01T09:00:00Z', '2025-08-02', 13],
])('활동 완료 지점 %s 이후만 조회하고 미접속 기간을 빠뜨리지 않는다', async (cursor, from, pages) => {
  api.state.mockResolvedValue({ cursorState: { activity: cursor }, serverTime: '2026-08-01T10:00:00Z' });
  await syncSamsungHealth();
  const calls = read.mock.calls.filter(([options]) => options.dataType === 'activity');
  expect(calls).toHaveLength(pages);
  expect(calls[0]?.[0].from).toBe(from);
  expect(calls[calls.length - 1]?.[0].to).toBe('2026-08-01');
  const batches = api.save.mock.calls.map(call => call[1]).filter(batch => batch.dataType === 'activity');
  expect(batches.filter(batch => batch.through)).toHaveLength(1);
  expect(batches[batches.length - 1]?.through).toBe('2026-08-01T10:00:00.000Z');
});
'''
p.write_text(s, encoding='utf-8')
p = root / 'preview/tests/내건강.spec.ts'
s = p.read_text(encoding='utf-8')
a = s.index("test('물은 앱 기록만")
b = s.index("test('서버 오류", a)
s = s[:a] + '''test('물은 새 기록을 추가하고 편집·삭제 동작을 제공하지 않는다', async ({ page }) => {
  await open(page);
  await page.getByRole('button', { name: '+ 수치 입력', exact: true }).click();
  await page.getByRole('button', { name: /물 섭취 섭취/ }).click();
  const before = await page.getByText(/섭취 기록 \\d+회/).innerText();
  await page.getByRole('button', { name: '0.5잔 (125ml)', exact: true }).click();
  await expect(page.getByText(/섭취 기록 \\d+회/)).not.toHaveText(before);
  await expect(page.getByRole('button', { name: /섭취량/ })).toHaveCount(0);
  await expect(page.getByRole('button', { name: /삭제|선택|변경사항 저장/ })).toHaveCount(0);
  await expect(page.getByRole('checkbox')).toHaveCount(0);
});

''' + s[b:]
p.write_text(s, encoding='utf-8')
print('증분 활동 조회·검진 새로고침 분리·직접 입력 추가 전용 화면 및 검증 갱신 완료')
