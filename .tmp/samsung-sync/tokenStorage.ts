import * as Keychain from 'react-native-keychain';
import { Tokens } from '../types/api';

const SERVICE = 'com.heapy.app.auth';
let cached: Tokens | null | undefined;
let hydration: Promise<Tokens | null> | undefined;
let writes: Promise<void> = Promise.resolve();
let version = 0;
const copy = (tokens: Tokens | null) => (tokens ? { ...tokens } : null);

// 작성자: 김진우 — 보안 저장소는 최초 한 번 읽고 메모리에서 계정을 확인한다. 저장·로그아웃 시 즉시 세대를 바꿔 이전 읽기가 새 세션을 덮지 못하게 한다.
export const tokenStorage = {
  async get(): Promise<Tokens | null> {
    await writes;
    if (cached !== undefined) return copy(cached);
    if (!hydration) {
      const readingVersion = version;
      const pending = Keychain.getGenericPassword({ service: SERVICE })
        .then(async value => {
          if (version !== readingVersion) return this.get();
          if (!value) {
            cached = null;
            return null;
          }
          try {
            cached = JSON.parse(value.password) as Tokens;
            return copy(cached);
          } catch {
            await this.clear();
            return null;
          }
        })
        .finally(() => {
          if (hydration === pending) hydration = undefined;
        });
      hydration = pending;
    }
    return copy(await hydration);
  },
  async save(tokens: Tokens): Promise<void> {
    const savingVersion = ++version;
    const snapshot = { ...tokens };
    cached = undefined;
    hydration = undefined;
    const task = writes.then(async () => {
      await Keychain.setGenericPassword('heapy-session', JSON.stringify(snapshot), {
        service: SERVICE,
        storage: Keychain.STORAGE_TYPE.AES_GCM_NO_AUTH,
      });
      if (version === savingVersion) cached = snapshot;
    });
    writes = task.catch(() => {});
    await task;
  },
  async clear(): Promise<void> {
    version++;
    cached = null;
    hydration = undefined;
    const task = writes.then(async () => {
      await Keychain.resetGenericPassword({ service: SERVICE });
    });
    writes = task.catch(() => {});
    await task;
  },
};
