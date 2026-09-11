from pathlib import Path
root=Path('C:/Users/jinwo/heapy-frontend')
p=root/'src/features/health/HealthScreen.tsx'
s=p.read_text(encoding='utf-8').replace("{route !== 'entries' && (", "{route !== 'entries' && !isCheckup && (")
s=s.replace('{!!syncNotice && (', '{!isCheckup && !!syncNotice && (').replace('{!!syncError && (', '{!isCheckup && !!syncError && (')
p.write_text(s,encoding='utf-8')
p=root/'preview/tests/내건강.spec.ts'
s=p.read_text(encoding='utf-8')
needle="    await page.getByRole('tab', { name: '건강검진', exact: true }).click();"
s=s.replace(needle,needle+"\n    await expect(page.getByRole('button', { name: '건강 기록 새로고침', exact: true })).toHaveCount(0);")
p.write_text(s,encoding='utf-8')
print('건강검진 새로고침 버튼과 동기화 안내 분리 완료')
