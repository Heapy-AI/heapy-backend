from pathlib import Path
root=Path('C:/Users/jinwo/heapy-frontend')
stage=Path('C:/Users/jinwo/heapy-backend/.tmp/samsung-sync')
for source,target in [('tokenStorage.ts','src/shared/storage/tokenStorage.ts'),('tokenStorage.test.ts','__tests__/tokenStorage.test.ts')]:
    (root/target).write_text((stage/source).read_text(encoding='utf-8'),encoding='utf-8')
p=root/'preview/tests/내건강.spec.ts'
s=p.read_text(encoding='utf-8')
old=Path('C:/Users/jinwo/heapy-backend/.tmp/health-ui/preview/내건강.spec.ts').read_text(encoding='utf-8')
a=old.index("test('물은")
b=old.index("test('서버 오류",a)
replacement=old[a:b]
a=s.index("test('물은")
b=s.index("test('서버 오류",a)
s=s[:a]+replacement+s[b:]
p.write_text(s,encoding='utf-8')
print('인증 메모리 캐시 및 물 관리 회귀 검증 복구 완료')
