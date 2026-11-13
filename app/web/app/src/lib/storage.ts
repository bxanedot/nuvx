export type StorageArea = 'localStorage' | 'sessionStorage';
const volatile = new Map<string, string>();

function storageKey(area: StorageArea, key: string): string {
  return `${area}:${key}`;
}

export function readStorage(area: StorageArea, key: string): string | null {
  try {
    const value = window[area].getItem(key);
    if (value !== null) return value;
  } catch {
  }
  return volatile.get(storageKey(area, key)) ?? null;
}

export function writeStorage(area: StorageArea, key: string, value: string): void {
  volatile.set(storageKey(area, key), value);
  try {
    window[area].setItem(key, value);
  } catch {}
}

export function removeStorage(area: StorageArea, key: string): void {
  volatile.delete(storageKey(area, key));
  try {
    window[area].removeItem(key);
  } catch {}
}
