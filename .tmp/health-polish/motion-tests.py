from pathlib import Path
p=Path('C:/Users/jinwo/heapy-frontend')
f=p/'src/features/dataConnection/DataConnectionScreen.tsx';s=f.read_text(encoding='utf-8');pos=s.index('function ConnectionAction');s=s[:pos]+s[pos:].replace('accessibilityRole="button"','accessibilityRole="button"\n      accessibilityLabel={label}',1);f.write_text(s,encoding='utf-8')
f=p/'preview/tests/건강디자인.spec.ts';s=f.read_text(encoding='utf-8')
s+='''
test('막대는 화면에 보일 때 아래에서 채워지고 작은 화면에서도 수치 카드가 넘치지 않는다',async({page})=>{
 await page.setViewportSize({width:320,height:844});
 await page.emulateMedia({reducedMotion:'no-preference'});
 await health(page);
 const card=page.getByTestId('health-metric-steps');
 expect(await card.evaluate(n=>n.scrollWidth<=n.clientWidth)).toBe(true);
 await page.evaluate(()=>{
   const state=window as typeof window & {healthBarSamples:number[]};
   state.healthBarSamples=[];
   new MutationObserver(records=>records.forEach(record=>{
     const target=record.target as Element;
     if(target===document.querySelector('[data-testid="health-chart-걸음 추이"] [data-testid="health-chart-bar"]')) {
       const height=Number(target.getAttribute('height'));
       if(Number.isFinite(height)&&state.healthBarSamples.length<300)state.healthBarSamples.push(height);
     }
   })).observe(document.body,{subtree:true,attributes:true,attributeFilter:['height']});
 });
 await page.getByRole('button',{name:/활동 기록/}).click();
 const chart=page.getByTestId('health-chart-걸음 추이');
 await chart.scrollIntoViewIfNeeded();
 await expect.poll(()=>page.evaluate(()=>new Set((window as typeof window & {healthBarSamples:number[]}).healthBarSamples).size)).toBeGreaterThan(2);
 await page.emulateMedia({reducedMotion:'reduce'});
 await expect.poll(async()=>Number(await chart.getByTestId('health-chart-bar').first().getAttribute('height'))).toBeGreaterThan(20);
 await chart.getByTestId('health-chart-hit').first().click();
 await expect(chart.getByTestId('health-chart-tooltip')).toBeVisible();
 const box=await chart.getByTestId('health-chart-tooltip').boundingBox();
 expect(box!.x).toBeGreaterThanOrEqual(0);expect(box!.x+box!.width).toBeLessThanOrEqual(320);
 await page.screenshot({path:'artifacts/health-polished-activity-320.png'});
});
'''
f.write_text(s,encoding='utf-8')
