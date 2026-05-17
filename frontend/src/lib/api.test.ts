import { AxiosError, type AxiosAdapter, type InternalAxiosRequestConfig } from 'axios';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  API_BASE_URL,
  api,
  authApi,
  bookingsApi,
  downloadBlob,
  eventsApi,
  holdsApi,
  organizerApi,
  problemMessage,
} from './api';
import { useAuthStore } from './authStore';
import type { ProblemDetails } from '../types';

function requestData(config: InternalAxiosRequestConfig): unknown {
  return typeof config.data === 'string' ? JSON.parse(config.data) : config.data;
}

describe('api client contract helpers', () => {
  const originalAdapter = api.defaults.adapter;

  afterEach(() => {
    api.defaults.adapter = originalAdapter;
    useAuthStore.getState().clear();
    vi.restoreAllMocks();
  });

  it('defaults to the /api/v1 backend contract', () => {
    expect(API_BASE_URL).toBe('/api/v1');
  });

  it('sends checkout idempotency keys', async () => {
    let captured: InternalAxiosRequestConfig | undefined;
    api.defaults.adapter = (async (config: InternalAxiosRequestConfig) => {
      captured = config;
      return {
        data: { bookings: [], total_amount: '0.00', created_at: new Date().toISOString() },
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      };
    }) as AxiosAdapter;

    await bookingsApi.checkout('hold-1', 'token-1', 'MOCK_PAY_OK', 'fixed-idempotency-key');

    expect(captured?.url).toBe('/bookings');
    expect(captured?.headers.get('Idempotency-Key')).toBe('fixed-idempotency-key');
    expect(requestData(captured as InternalAxiosRequestConfig)).toMatchObject({
      hold_group_id: 'hold-1',
      hold_token: 'token-1',
      payment_token: 'MOCK_PAY_OK',
    });
  });

  it('posts organizer create events to the canonical SPEC endpoint and row payload', async () => {
    let captured: InternalAxiosRequestConfig | undefined;
    api.defaults.adapter = (async (config: InternalAxiosRequestConfig) => {
      captured = config;
      return {
        data: { id: 'event-1', name: 'Mozart Gala', total_seats: 20 },
        status: 201,
        statusText: 'Created',
        headers: {},
        config,
      };
    }) as AxiosAdapter;

    await organizerApi.createEvent({
      name: 'Mozart Gala',
      event_date: '2026-06-01T19:00:00Z',
      venue_name: 'Inha Hall',
      rows: [{ label: 'A', seat_count: 20, tier: 'VIP', price: '120.00' }],
    });

    expect(captured?.method).toBe('post');
    expect(captured?.url).toBe('/events');
    expect(requestData(captured as InternalAxiosRequestConfig)).toMatchObject({
      rows: [{ label: 'A', seat_count: 20, tier: 'VIP', price: '120.00' }],
    });
  });

  it('sends logout refresh token in both header and body for backend compatibility', async () => {
    useAuthStore
      .getState()
      .setTokens({ access_token: 'not-a-jwt', refresh_token: 'refresh-1', token_type: 'Bearer', expires_in: 1800 });
    let captured: InternalAxiosRequestConfig | undefined;
    api.defaults.adapter = (async (config: InternalAxiosRequestConfig) => {
      captured = config;
      return { data: undefined, status: 204, statusText: 'No Content', headers: {}, config };
    }) as AxiosAdapter;

    await authApi.logout();

    expect(captured?.url).toBe('/auth/logout');
    expect(captured?.headers.get('X-Refresh-Token')).toBe('refresh-1');
    expect(requestData(captured as InternalAxiosRequestConfig)).toMatchObject({
      refresh_token: 'refresh-1',
      refreshToken: 'refresh-1',
    });
  });

  it('releases holds through the canonical DELETE path and falls back to legacy release', async () => {
    const calls: InternalAxiosRequestConfig[] = [];
    api.defaults.adapter = (async (config: InternalAxiosRequestConfig) => {
      calls.push(config);
      if (config.url === '/seats/hold/hold-1') {
        throw new AxiosError('not found', '404', config, undefined, {
          data: {},
          status: 404,
          statusText: 'Not Found',
          headers: {},
          config,
        });
      }
      return { data: undefined, status: 204, statusText: 'No Content', headers: {}, config };
    }) as AxiosAdapter;

    await holdsApi.release('hold-1');

    expect(calls.map((call) => `${call.method} ${call.url}`)).toEqual([
      'delete /seats/hold/hold-1',
      'delete /seats/hold',
    ]);
    expect(requestData(calls[1])).toMatchObject({ hold_group_id: 'hold-1', holdGroupId: 'hold-1' });
  });

  it('derives incomplete legacy hold responses by refetching the seat map', async () => {
    const calls: string[] = [];
    api.defaults.adapter = (async (config: InternalAxiosRequestConfig) => {
      calls.push(`${config.method} ${config.url}`);
      if (config.url === '/seats/hold')
        return {
          data: { holdGroupId: 'hold-1', holdToken: 'tok' },
          status: 200,
          statusText: 'OK',
          headers: {},
          config,
        };
      return {
        data: {
          event_id: 'event-1',
          rows: [{ label: 'A', seats: [{ id: 's1', number: 1, tier: 'VIP', price: '120.00', status: 'AVAILABLE' }] }],
        },
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      };
    }) as AxiosAdapter;

    const hold = await holdsApi.hold('event-1', ['s1']);

    expect(calls).toEqual(['post /seats/hold', 'get /events/event-1/seats']);
    expect(hold).toMatchObject({
      hold_group_id: 'hold-1',
      hold_token: 'tok',
      total_amount: '120.00',
      seats: [{ id: 's1', row: 'A', number: 1 }],
    });
  });

  it('normalizes event seat maps at the client boundary', async () => {
    api.defaults.adapter = (async (config: InternalAxiosRequestConfig) => ({
      data: { eventId: 'event-1', heldSeatIds: ['s1'], seats: [{ id: 's1', rowLabel: 'A', seatNumber: 1, price: 25 }] },
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
    })) as AxiosAdapter;

    const map = await eventsApi.seats('event-1');

    expect(map.rows[0].seats[0]).toMatchObject({ id: 's1', status: 'HELD', price: '25.00' });
  });

  it('surfaces RFC7807 trace, retry and seat conflict details', () => {
    const problem: ProblemDetails = {
      title: 'Conflict',
      detail: 'Seats unavailable',
      trace_id: 'trace-123',
      retry_after_seconds: 4,
      conflicting_seat_ids: ['A1', 'A2'],
    };
    const error = new AxiosError<ProblemDetails>('Request failed', '409', undefined, undefined, {
      data: problem,
      status: 409,
      statusText: 'Conflict',
      headers: {},
      config: {} as InternalAxiosRequestConfig,
    });

    expect(problemMessage(error)).toContain('Seats unavailable');
    expect(problemMessage(error)).toContain('Trace ID: trace-123');
    expect(problemMessage(error)).toContain('Retry after: 4s');
    expect(problemMessage(error)).toContain('Conflicting seats: A1, A2');
  });

  it('downloads authenticated PDF blobs through a generated object URL', async () => {
    const createObjectURL = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:ticket');
    const revokeObjectURL = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);

    await downloadBlob(new Blob(['pdf'], { type: 'application/pdf' }), 'ticket-1.pdf');

    expect(createObjectURL).toHaveBeenCalledOnce();
    expect(click).toHaveBeenCalledOnce();
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:ticket');
  });
});
