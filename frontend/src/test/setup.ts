import '@testing-library/jest-dom/vitest';

function makeStorage(): Storage {
  let values: Record<string, string> = {};
  return {
    get length() {
      return Object.keys(values).length;
    },
    clear: () => {
      values = {};
    },
    getItem: (key: string) => values[key] ?? null,
    key: (index: number) => Object.keys(values)[index] ?? null,
    removeItem: (key: string) => {
      delete values[key];
    },
    setItem: (key: string, value: string) => {
      values[key] = value;
    },
  };
}

Object.defineProperty(globalThis, 'localStorage', { value: makeStorage(), configurable: true });
Object.defineProperty(globalThis, 'sessionStorage', { value: makeStorage(), configurable: true });

if (!URL.createObjectURL) URL.createObjectURL = () => 'blob:test';
if (!URL.revokeObjectURL) URL.revokeObjectURL = () => undefined;
