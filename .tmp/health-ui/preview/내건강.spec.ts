// 작성자: 김진우 — 합성 데이터로 화면 이동·출처 제한·입력 흐름을 검증한다.
import {test,expect} from '@playwright/test';
test.use({viewport:{width:390,height:844},trace:'off',video:'off'});
async function open(page:import('@playwright/test').Page){await page.goto('/?frame=1#Home');await page.getByRole('tab',{name:'내 건강',exact:true}).click();}
test('통합 화면에서 상세 그래프와 모든 입력 화면으로 이동한다',async({page})=>{
 await open(page);await expect(page.getByText('전체 건강 흐름',{exact:true})).toBeVisible();await page.screenshot({path:'artifacts/health-overview.png',fullPage:true});
 await page.getByRole('button',{name:/생체 기록/}).click();await expect(page.getByText('체중 변화',{exact:true})).toBeVisible();await page.getByRole('radio',{name:'90일',exact:true}).click();await expect(page.getByText(/구간별 기록일 평균/)).toBeVisible();await page.getByText('수치표 보기',{exact:true}).first().click();await page.screenshot({path:'artifacts/health-bio.png',fullPage:true});
 await page.getByRole('button',{name:'+ 수치 입력',exact:true}).click();await expect(page.getByLabel('입력할 항목 검색')).toBeVisible();await page.getByRole('button',{name:/수면 취침/}).click();await expect(page.getByText('수면 기록 저장',{exact:true})).toBeVisible();await page.screenshot({path:'artifacts/health-sleep-entry.png',fullPage:true});
});
test('물은 앱 기록만 편집하며 선택 삭제에는 확인이 필요하다',async({page})=>{
 await open(page);await page.getByRole('button',{name:'+ 수치 입력',exact:true}).click();await page.getByRole('button',{name:/물 섭취 섭취/}).click();await expect(page.getByText('삼성헬스 · 편집 불가',{exact:false}).first()).toBeVisible();
 await page.getByRole('button',{name:'0.5잔 (125ml)',exact:true}).click();await expect(page.getByRole('button',{name:/섭취량.*125/})).toBeVisible();await page.getByRole('button',{name:/섭취량.*125/}).click();await expect(page.getByText('선택 125 mL',{exact:true})).toBeVisible();await page.screenshot({path:'artifacts/health-water-edit.png',fullPage:true});
 await page.getByRole('button',{name:'물 기록 뒤로',exact:true}).click();await page.getByRole('button',{name:'선택',exact:true}).click();await page.getByRole('checkbox').filter({hasText:'삼성헬스'}).first().isDisabled().then(value=>expect(value).toBe(true));
 await page.getByText('전체 선택',{exact:true}).click();await page.getByText(/선택한 기록 \d+개 삭제/).click();await expect(page.getByText('물 기록을 삭제할까요?',{exact:true})).toBeVisible();
});
test('서버 오류를 샘플 건강 수치로 대체하지 않는다',async({page})=>{
 await page.goto('/?frame=1&healthState=error#Home');await page.getByRole('tab',{name:'내 건강',exact:true}).click();await expect(page.getByText('일부 건강 기록을 불러오지 못했어요.')).toBeVisible();
});
