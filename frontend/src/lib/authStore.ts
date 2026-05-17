import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';
import type { AuthTokens, AuthUser, Role } from '../types';

const EXPIRY_SKEW_SECONDS = 15;

function decodeBase64Url(value: string): string {
  const normalized = value.replace(/-/g, '+').replace(/_/g, '/');
  const padded = `${normalized}${'='.repeat((4 - (normalized.length % 4)) % 4)}`;
  return atob(padded);
}

export function decodeJwt(token: string): AuthUser {
  try {
    const [, payload] = token.split('.');
    if (!payload) throw new Error('Missing JWT payload');
    const json = JSON.parse(decodeBase64Url(payload)) as Record<string, unknown>;
    const rawRole = json.role ?? json.authorities ?? json.scope;
    const roleText = Array.isArray(rawRole) ? String(rawRole[0]) : String(rawRole ?? 'CUSTOMER');
    const role = roleText.replace('ROLE_', '').toUpperCase() as Role;
    return {
      sub: String(json.sub ?? ''),
      id: json.uid ? String(json.uid) : undefined,
      email: String(json.email ?? json.sub ?? ''),
      full_name: String(json.full_name ?? ''),
      role,
      exp: Number(json.exp ?? 0),
    };
  } catch {
    return { role: 'CUSTOMER' };
  }
}

export function isJwtExpired(tokenOrUser?: string | AuthUser, skewSeconds = EXPIRY_SKEW_SECONDS): boolean {
  const exp = typeof tokenOrUser === 'string' ? decodeJwt(tokenOrUser).exp : tokenOrUser?.exp;
  if (!exp) return true;
  return exp <= Math.floor(Date.now() / 1000) + skewSeconds;
}

interface AuthState {
  accessToken?: string;
  refreshToken?: string;
  user?: AuthUser;
  setTokens: (tokens: AuthTokens) => void;
  clear: () => void;
  isAuthenticated: () => boolean;
  hasRole: (roles?: Role[]) => boolean;
  accessTokenExpired: () => boolean;
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set, get) => ({
      setTokens: (tokens) =>
        set({
          accessToken: tokens.access_token,
          refreshToken: tokens.refresh_token,
          user: decodeJwt(tokens.access_token),
        }),
      clear: () => set({ accessToken: undefined, refreshToken: undefined, user: undefined }),
      accessTokenExpired: () => isJwtExpired(get().user),
      isAuthenticated: () => Boolean(get().accessToken && get().refreshToken && !get().accessTokenExpired()),
      hasRole: (roles) => !roles?.length || roles.includes(get().user?.role ?? 'CUSTOMER'),
    }),
    { name: 'ticket-system-dad-auth', storage: createJSONStorage(() => localStorage) }
  )
);

export const getAuthState = () => useAuthStore.getState();
