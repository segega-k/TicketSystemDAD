import { describe, expect, it, vi } from 'vitest';
import {
  normalizeBookingResponse,
  normalizeBookingsPage,
  normalizeDashboard,
  normalizeHoldResponse,
  normalizeSeatMap,
} from './adapters';

describe('API response adapters', () => {
  it('normalizes SPEC grouped seat maps', () => {
    const map = normalizeSeatMap({
      event_id: 'event-1',
      rows: [{ label: 'A', seats: [{ id: 's1', number: 1, tier: 'VIP', price: '120.00', status: 'HELD' }] }],
      fetched_at: 'now',
    });
    expect(map.rows[0].label).toBe('A');
    expect(map.rows[0].seats[0]).toMatchObject({ id: 's1', number: 1, status: 'HELD' });
  });

  it('normalizes legacy flat seat maps with booked/held id lists', () => {
    const map = normalizeSeatMap({
      eventId: 'event-1',
      bookedSeatIds: ['s2'],
      heldSeatIds: ['s3'],
      seats: [
        { id: 's1', rowLabel: 'B', seatNumber: 1, tier: 'STANDARD', price: 60 },
        { id: 's2', rowLabel: 'B', seatNumber: 2, tier: 'STANDARD', price: 60 },
        { id: 's3', rowLabel: 'A', seatNumber: 1, tier: 'VIP', price: 120 },
      ],
    });
    expect(map.rows.map((row) => row.label)).toEqual(['B', 'A']);
    expect(map.rows[0].seats[1].status).toBe('BOOKED');
    expect(map.rows[1].seats[0].status).toBe('HELD');
  });

  it('derives hold expiry, seats and total when legacy response omits them', () => {
    vi.setSystemTime(new Date('2026-05-16T10:00:00Z'));
    const seatMap = normalizeSeatMap({
      event_id: 'event-1',
      rows: [{ label: 'A', seats: [{ id: 's1', number: 1, tier: 'VIP', price: '120.00', status: 'AVAILABLE' }] }],
    });
    const hold = normalizeHoldResponse(
      { holdGroupId: 'hg1', holdToken: 'tok' },
      { eventId: 'event-1', seatIds: ['s1'], seatMap, now: new Date('2026-05-16T10:00:00Z') }
    );
    expect(hold).toMatchObject({
      hold_group_id: 'hg1',
      hold_token: 'tok',
      event_id: 'event-1',
      total_amount: '120.00',
    });
    expect(hold.expires_at).toBe('2026-05-16T10:10:00.000Z');
    expect(hold.seats[0]).toMatchObject({ row: 'A', number: 1 });
    vi.useRealTimers();
  });

  it('normalizes SPEC booking pages and legacy single booking/checkout responses', () => {
    expect(
      normalizeBookingsPage({
        items: [
          { bookings: [{ id: 'b1', seat_id: 's1', row: 'A', number: 1, amount: '25.00' }], created_at: 'created' },
        ],
        next_cursor: 'next',
      })
    ).toMatchObject({ next_cursor: 'next', items: [{ id: 'b1', created_at: 'created' }] });
    const legacy = normalizeBookingResponse({
      id: 'b2',
      seatId: 's2',
      rowLabel: 'B',
      seatNumber: 2,
      amount: 30,
      createdAt: 'created-2',
      ticketPdfUrl: '/ticket.pdf',
    });
    expect(legacy.bookings).toHaveLength(1);
    expect(legacy).toMatchObject({ total_amount: '30.00', created_at: 'created-2' });
    expect(legacy.bookings[0]).toMatchObject({ id: 'b2', seat_id: 's2', row: 'B', number: 2 });
  });

  it('normalizes SPEC and legacy dashboard shapes', () => {
    expect(
      normalizeDashboard({
        sold: 2,
        available: 8,
        revenue: { gross: '20.00', refunded: '5.00', net: '15.00' },
        by_tier: [],
        daily_sales_last_30d: [],
      })
    ).toMatchObject({ sold: 2, available: 8, revenue: { net: '15.00' } });
    expect(
      normalizeDashboard({
        eventId: 'event-1',
        eventName: 'Legacy',
        totalSeats: 10,
        ticketsSold: 3,
        revenueCents: 12345,
        byStatus: { CANCELLED: 1 },
        byTier: [{ tier: 'VIP', total: 5, ticketsSold: 2, revenueCents: 10000 }],
        dailySales: [{ date: '2026-05-16', ticketsSold: 3, revenueCents: 12345 }],
      })
    ).toMatchObject({
      event_id: 'event-1',
      event_name: 'Legacy',
      sold: 3,
      cancelled: 1,
      available: 7,
      revenue: { gross: '123.45', net: '123.45' },
      by_tier: [{ revenue: '100.00' }],
      daily_sales_last_30d: [{ revenue: '123.45' }],
    });
  });
});
