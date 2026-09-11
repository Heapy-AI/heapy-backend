// 작성자: 김진우 — 반복 보안 저장소 읽기 제거와 계정 전환 경합을 검증한다.
jest.mock('react-native-keychain', () => ({
  getGenericPassword: jest.fn(), setGenericPassword: jest.fn(), resetGenericPassword: jest.fn(),
  STORAGE_TYPE: { AES_GCM_NO_AUTH: 'AES_GCM_NO_AUTH' },
}));
const tokens = (accessToken: string) => ({ accessToken, refreshToken: 'test-refresh', tokenType: 'Bearer', expiresAt: '2027-01-01T00:00:00Z' });
function setup() {
  jest.resetModules();
  const keychain = require('react-native-keychain');
  keychain.getGenericPassword.mockResolvedValue({ password: JSON.stringify(tokens('old')) });
  keychain.setGenericPassword.mockResolvedValue(true);
  keychain.resetGenericPassword.mockResolvedValue(true);
  return { keychain, storage: require('../src/shared/storage/tokenStorage').tokenStorage };
}
test('동시에 여러 요청이 시작돼도 보안 저장소는 한 번만 읽는다', async () => {
  const { storage, keychain } = setup();
  const values = await Promise.all(Array.from({ length: 30 }, () => storage.get()));
  expect(values.every(value => value.accessToken === 'old')).toBe(true);
  values[0].accessToken = 'modified';
  expect((await storage.get()).accessToken).toBe('old');
  expect(keychain.getGenericPassword).toHaveBeenCalledTimes(1);
});
test('늦게 끝난 보안 저장소 읽기가 로그아웃을 되돌리지 않는다', async () => {
  const { storage, keychain } = setup();
  let finish!: (value: unknown) => void;
  keychain.getGenericPassword.mockReturnValue(new Promise(resolve => { finish = resolve; }));
  const reading = storage.get();
  await Promise.resolve();
  await storage.clear();
  finish({ password: JSON.stringify(tokens('old')) });
  expect(await reading).toBeNull();
  expect(await storage.get()).toBeNull();
});
test('읽는 도중 계정이 바뀌면 새 계정만 반환한다', async () => {
  const { storage, keychain } = setup();
  let finish!: (value: unknown) => void;
  keychain.getGenericPassword.mockReturnValue(new Promise(resolve => { finish = resolve; }));
  const reading = storage.get();
  await Promise.resolve();
  await storage.save(tokens('new'));
  finish({ password: JSON.stringify(tokens('old')) });
  expect((await reading).accessToken).toBe('new');
});
test('저장과 로그아웃을 겹쳐 호출해도 마지막 작업을 보존한다', async () => {
  const { storage, keychain } = setup();
  await Promise.all([storage.save(tokens('new')), storage.clear()]);
  expect(await storage.get()).toBeNull();
  expect(keychain.setGenericPassword.mock.invocationCallOrder[0]).toBeLessThan(keychain.resetGenericPassword.mock.invocationCallOrder[0]);
});
test('저장 실패를 성공한 세션으로 캐시하지 않는다', async () => {
  const { storage, keychain } = setup();
  keychain.setGenericPassword.mockRejectedValueOnce(new Error('저장 실패'));
  await expect(storage.save(tokens('new'))).rejects.toThrow('저장 실패');
  expect((await storage.get()).accessToken).toBe('old');
});
test('손상된 저장값은 제거하고 재사용하지 않는다', async () => {
  const { storage, keychain } = setup();
  keychain.getGenericPassword.mockResolvedValue({ password: '{' });
  expect(await storage.get()).toBeNull();
  expect(keychain.resetGenericPassword).toHaveBeenCalledTimes(1);
});
