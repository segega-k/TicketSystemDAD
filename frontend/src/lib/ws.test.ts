import { describe, expect, it } from 'vitest';
import { nextReconnectDelay } from './ws';

describe('websocket reconnect backoff', () => {
  it('backs off exponentially and caps at 30 seconds', () => {
    expect(nextReconnectDelay(0)).toBe(1000);
    expect(nextReconnectDelay(1000)).toBe(2000);
    expect(nextReconnectDelay(16000)).toBe(30000);
    expect(nextReconnectDelay(30000)).toBe(30000);
  });
});
