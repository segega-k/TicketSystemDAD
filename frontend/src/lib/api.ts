import axios, { type AxiosError, type AxiosRequestConfig, type InternalAxiosRequestConfig } from 'axios';
import { getAuthState, useAuthStore } from './authStore';
import { makeIdempotencyKey } from './format';
import type {
  AuthTokens,
  CancelResponse,
  CreateEventRequest,
  HoldResponse,
  ProblemDetails,
  RegisterRequest,
} from '../types';
import {
  normalizeBookingResponse,
  normalizeBookingsPage,
  normalizeDashboard,
  normalizeEventDetail,
  normalizeEventPage,
  normalizeHoldResponse,
  normalizeSeatMap,
} from './adapters';

export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '/api/v1';

export const api = axios.create({ baseURL: API_BASE_URL, headers: { 'Content-Type': 'application/json' } });

function loginRedirectUrl(): string {
  return `/login?next=${encodeURIComponent(`${window.location.pathname}${window.location.search}`)}`;
}

function clearAuthAndRedirect(): void {
  useAuthStore.getState().clear();
  if (!window.location.pathname.startsWith('/login')) window.location.assign(loginRedirectUrl());
}

api.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = getAuthState().accessToken;
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

api.interceptors.response.use(
  (response) => response,
  async (error: AxiosError<ProblemDetails>) => {
    const original = error.config as (AxiosRequestConfig & { _retry?: boolean }) | undefined;
    const refreshToken = getAuthState().refreshToken;
    if (error.response?.status === 401 && original?.url?.includes('/auth/refresh')) {
      clearAuthAndRedirect();
      return Promise.reject(error);
    }
    if (error.response?.status === 401 && original?._retry) {
      clearAuthAndRedirect();
      return Promise.reject(error);
    }
    if (error.response?.status === 401 && original && refreshToken) {
      original._retry = true;
      try {
        const { data } = await axios.post<AuthTokens>(`${API_BASE_URL}/auth/refresh`, { refresh_token: refreshToken });
        useAuthStore.getState().setTokens(data);
        original.headers = { ...(original.headers ?? {}), Authorization: `Bearer ${data.access_token}` };
        return api(original);
      } catch (refreshError) {
        clearAuthAndRedirect();
        return Promise.reject(refreshError);
      }
    }
    return Promise.reject(error);
  }
);

export function problemMessage(error: unknown): string {
  if (!axios.isAxiosError<ProblemDetails>(error)) return error instanceof Error ? error.message : 'Unexpected error';
  const problem = error.response?.data;
  if (!problem) return error.message;
  const messages = [problem.detail ?? problem.title ?? error.message];
  if (problem.trace_id) messages.push(`Trace ID: ${problem.trace_id}`);
  if (problem.retry_after_seconds !== undefined) messages.push(`Retry after: ${problem.retry_after_seconds}s`);
  if (Array.isArray(problem.conflicting_seat_ids) && problem.conflicting_seat_ids.length)
    messages.push(`Conflicting seats: ${problem.conflicting_seat_ids.join(', ')}`);
  return messages.filter(Boolean).join(' · ');
}

export async function downloadBlob(blob: Blob, filename: string): Promise<void> {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

export async function downloadTicketPdf(bookingId: string): Promise<void> {
  const blob = await bookingsApi.ticketBlob(bookingId);
  await downloadBlob(blob, `ticket-${bookingId}.pdf`);
}

export const authApi = {
  login: async (email: string, password: string) =>
    (await api.post<AuthTokens>('/auth/login', { email, password })).data,
  register: async (payload: RegisterRequest) => (await api.post('/auth/register', payload)).data,
  logout: async () => {
    const refresh = getAuthState().refreshToken;
    if (refresh)
      await api.post(
        '/auth/logout',
        { refresh_token: refresh, refreshToken: refresh },
        { headers: { 'X-Refresh-Token': refresh } }
      );
  },
};

export const eventsApi = {
  list: async (params: { q?: string; cursor?: string; limit?: number }) =>
    normalizeEventPage((await api.get<unknown>('/events', { params })).data),
  detail: async (id: string) => normalizeEventDetail((await api.get<unknown>(`/events/${id}`)).data),
  seats: async (id: string) => normalizeSeatMap((await api.get<unknown>(`/events/${id}/seats`)).data, id),
};

async function deriveHold(raw: unknown, eventId: string, seatIds: string[]): Promise<HoldResponse> {
  const initial = normalizeHoldResponse(raw, { eventId, seatIds });
  if (initial.seats.length >= seatIds.length && Number(initial.total_amount) > 0) return initial;
  try {
    const seatMap = await eventsApi.seats(eventId);
    return normalizeHoldResponse(raw, { eventId, seatIds, seatMap });
  } catch {
    return initial;
  }
}

export const holdsApi = {
  hold: async (eventId: string, seatIds: string[]) =>
    deriveHold(
      (await api.post<unknown>('/seats/hold', { event_id: eventId, seat_ids: seatIds })).data,
      eventId,
      seatIds
    ),
  release: async (holdGroupId: string) => {
    try {
      await api.delete(`/seats/hold/${holdGroupId}`);
    } catch (error) {
      if (!axios.isAxiosError(error) || (error.response?.status !== 404 && error.response?.status !== 405)) throw error;
      await api.delete('/seats/hold', { data: { hold_group_id: holdGroupId, holdGroupId } });
    }
  },
};

export const bookingsApi = {
  checkout: async (
    hold_group_id: string,
    hold_token: string,
    payment_token: string,
    idempotencyKey = makeIdempotencyKey()
  ) =>
    normalizeBookingResponse(
      (
        await api.post<unknown>(
          '/bookings',
          { hold_group_id, hold_token, payment_token },
          { headers: { 'Idempotency-Key': idempotencyKey } }
        )
      ).data
    ),
  me: async (params: { cursor?: string; limit?: number }) =>
    normalizeBookingsPage((await api.get<unknown>('/users/me/bookings', { params })).data),
  cancel: async (bookingId: string, reason: string) =>
    (await api.post<CancelResponse>(`/bookings/${bookingId}/cancel`, { reason })).data,
  ticketBlob: async (bookingId: string) =>
    (await api.get<Blob>(`/bookings/${bookingId}/ticket.pdf`, { responseType: 'blob' })).data,
};

export const organizerApi = {
  createEvent: async (payload: CreateEventRequest) =>
    normalizeEventDetail(
      (
        await api.post<unknown>('/events', {
          ...payload,
          rows: payload.rows.map((row) => ({
            label: row.label,
            seat_count: row.seat_count,
            tier: row.tier,
            price: row.price,
          })),
        })
      ).data
    ),
  dashboard: async (eventId: string) =>
    normalizeDashboard((await api.get<unknown>(`/organizer/events/${eventId}/dashboard`)).data, eventId),
};
