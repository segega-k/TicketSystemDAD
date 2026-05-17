import { beforeEach, describe, expect, it } from 'vitest';
import { isJwtExpired, useAuthStore } from './authStore';

function jwt(exp: number, role = 'CUSTOMER'): string {
  const payload = btoa(JSON.stringify({ sub: 'u1', email: 'u@example.com', role, exp }))
    .replace(/=/g, '')
    .replace(/\+/g, '-')
    .replace(/\//g, '_');
  return `header.${payload}.signature`;
}

describe('auth store expiry awareness', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    localStorage.clear();
  });

  it('treats expired access tokens as unauthenticated', () => {
    useAuthStore
      .getState()
      .setTokens({
        access_token: jwt(Math.floor(Date.now() / 1000) - 60),
        refresh_token: 'refresh',
        token_type: 'Bearer',
        expires_in: 3600,
      });
    expect(useAuthStore.getState().accessTokenExpired()).toBe(true);
    expect(useAuthStore.getState().isAuthenticated()).toBe(false);
  });

  it('accepts valid unexpired tokens and decodes role', () => {
    useAuthStore
      .getState()
      .setTokens({
        access_token: jwt(Math.floor(Date.now() / 1000) + 3600, 'ORGANIZER'),
        refresh_token: 'refresh',
        token_type: 'Bearer',
        expires_in: 3600,
      });
    expect(isJwtExpired(useAuthStore.getState().user)).toBe(false);
    expect(useAuthStore.getState().isAuthenticated()).toBe(true);
    expect(useAuthStore.getState().hasRole(['ORGANIZER'])).toBe(true);
  });
});
