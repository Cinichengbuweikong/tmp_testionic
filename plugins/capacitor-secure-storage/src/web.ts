import { WebPlugin } from '@capacitor/core';
import type {
  BiometryType,
  SecureStorageAccessibility,
  SecureStoragePlugin,
} from './definitions';

/**
 * Web 端实现：WebCrypto AES-GCM + 不可导出的 CryptoKey。
 *
 * 密钥生成后以 `extractable: false` 存进 IndexedDB，页面里的任何脚本（包括 XSS）
 * 都拿不到原始密钥字节，但**能调用同样的 API 解密** —— 所以这只是「防明文落盘」，
 * 不是系统级安全边界。浏览器清站点数据 / 用户清缓存会同时失去密钥与密文。
 */

const DB_NAME = 'capacitor-secure-storage';
const DB_STORE = 'keys';
const KEY_ID = 'aes-gcm-v1';
const PREFIX = 'capacitor-secure-storage:';
const INDEX_KEY = 'capacitor-secure-storage:__index';

function toBase64(bytes: Uint8Array): string {
  let binary = '';
  for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
  return btoa(binary);
}

function fromBase64(value: string): Uint8Array<ArrayBuffer> {
  const binary = atob(value);
  // 显式用 ArrayBuffer 构造，避免 TypeScript 5.9 把 Uint8Array 推断成 ArrayBufferLike
  const buffer = new ArrayBuffer(binary.length);
  const out = new Uint8Array(buffer);
  for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i);
  return out;
}

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, 1);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(DB_STORE)) db.createObjectStore(DB_STORE);
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error ?? new Error('indexedDB open failed'));
  });
}

async function idbGet<T>(key: string): Promise<T | undefined> {
  const db = await openDb();
  try {
    return await new Promise<T | undefined>((resolve, reject) => {
      const tx = db.transaction(DB_STORE, 'readonly');
      const req = tx.objectStore(DB_STORE).get(key);
      req.onsuccess = () => resolve(req.result as T | undefined);
      req.onerror = () => reject(req.error ?? new Error('indexedDB get failed'));
    });
  } finally {
    // 用 try/finally 而不是 Promise.prototype.finally：后者是 ES2018，
    // 而本插件 tsconfig 的 lib/target 是 es2017（从零构建会报 TS2550）。
    db.close();
  }
}

async function idbPut(key: string, value: unknown): Promise<void> {
  const db = await openDb();
  try {
    return await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(DB_STORE, 'readwrite');
      tx.objectStore(DB_STORE).put(value, key);
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error ?? new Error('indexedDB put failed'));
    });
  } finally {
    db.close();
  }
}

async function getKey(): Promise<CryptoKey> {
  const existing = await idbGet<CryptoKey>(KEY_ID);
  if (existing) return existing;

  const key = await crypto.subtle.generateKey(
    { name: 'AES-GCM', length: 256 },
    false, // 不可导出：JS 拿不到密钥字节
    ['encrypt', 'decrypt'],
  );
  await idbPut(KEY_ID, key);
  return key;
}

function readIndex(): string[] {
  try {
    const raw = localStorage.getItem(INDEX_KEY);
    return raw ? (JSON.parse(raw) as string[]) : [];
  } catch {
    return [];
  }
}

function writeIndex(keys: string[]): void {
  localStorage.setItem(INDEX_KEY, JSON.stringify(keys));
}

export class SecureStorageWeb extends WebPlugin implements SecureStoragePlugin {
  private async encrypt(value: string): Promise<string> {
    const key = await getKey();
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const ciphertext = await crypto.subtle.encrypt(
      { name: 'AES-GCM', iv },
      key,
      new TextEncoder().encode(value),
    );
    return `${toBase64(iv)}.${toBase64(new Uint8Array(ciphertext))}`;
  }

  private async decrypt(payload: string): Promise<string> {
    const key = await getKey();
    const [ivPart, dataPart] = payload.split('.');
    if (!ivPart || !dataPart) throw new Error('CORRUPTED_DATA');
    const plaintext = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv: fromBase64(ivPart) },
      key,
      fromBase64(dataPart),
    );
    return new TextDecoder().decode(plaintext);
  }

  async set(options: {
    key: string;
    value: string;
    requireBiometric?: boolean;
    accessibility?: SecureStorageAccessibility;
  }): Promise<{ key: string; requireBiometric: boolean }> {
    if (options.requireBiometric) {
      throw this.unavailable('BIOMETRY_UNSUPPORTED: Web 端没有系统级生物识别存储');
    }
    if (typeof options.value !== 'string') throw new Error('VALUE_MUST_BE_STRING');

    localStorage.setItem(PREFIX + options.key, await this.encrypt(options.value));
    const index = readIndex();
    if (!index.includes(options.key)) {
      index.push(options.key);
      writeIndex(index);
    }
    return { key: options.key, requireBiometric: false };
  }

  async get(options: { key: string; reason?: string }): Promise<{
    key: string;
    value: string | null;
    requireBiometric: boolean;
    notFound: boolean;
  }> {
    const stored = localStorage.getItem(PREFIX + options.key);
    if (stored === null) {
      return { key: options.key, value: null, requireBiometric: false, notFound: true };
    }
    return {
      key: options.key,
      value: await this.decrypt(stored),
      requireBiometric: false,
      notFound: false,
    };
  }

  async remove(options: { key: string }): Promise<{ key: string; deleted: boolean }> {
    const existed = localStorage.getItem(PREFIX + options.key) !== null;
    localStorage.removeItem(PREFIX + options.key);
    writeIndex(readIndex().filter((k) => k !== options.key));
    return { key: options.key, deleted: existed };
  }

  async keys(): Promise<{ keys: string[] }> {
    return { keys: readIndex() };
  }

  async clear(): Promise<{ cleared: number }> {
    const index = readIndex();
    for (const key of index) localStorage.removeItem(PREFIX + key);
    writeIndex([]);
    return { cleared: index.length };
  }

  async isBiometryAvailable(): Promise<{
    available: boolean;
    type: BiometryType;
    enrolled: boolean;
    reason?: string;
  }> {
    return {
      available: false,
      type: 'none',
      enrolled: false,
      reason: 'WEB_HAS_NO_BIOMETRY_STORAGE',
    };
  }

  async authenticate(): Promise<{ success: boolean; reason?: string }> {
    return { success: false, reason: 'NOT_SUPPORTED_ON_WEB' };
  }

  async getInfo(): Promise<{
    platform: 'web';
    backend: string;
    itemCount: number;
    biometry: { available: boolean; type: BiometryType; enrolled: boolean };
  }> {
    return {
      platform: 'web',
      backend: 'webcrypto-aes-gcm-256 (non-extractable key in IndexedDB)',
      itemCount: readIndex().length,
      biometry: { available: false, type: 'none', enrolled: false },
    };
  }
}
