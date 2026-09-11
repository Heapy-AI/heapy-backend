from pathlib import Path
p=Path('C:/Users/jinwo/heapy-frontend/preview/tests/내건강.spec.ts')
s=p.read_text(encoding='utf-8').replace("getByText('저장',", "getByText('저장하기',").replace("name: '직접 입력 뒤로'", "name: '입력 취소'")
p.write_text(s,encoding='utf-8')
