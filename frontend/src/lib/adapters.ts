import type {
  BookingRecord,
  BookingResponse,
  CursorPage,
  Dashboard,
  EventDetail,
  EventListItem,
  HoldResponse,
  HoldSeat,
  Seat,
  SeatMapResponse,
  SeatRow,
  SeatStatus,
} from '../types';

type UnknownRecord = Record<string, unknown>;

function isRecord(value: unknown): value is UnknownRecord {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function stringValue(value: unknown, fallback = ''): string {
  if (value === null || value === undefined) return fallback;
  return String(value);
}

function optionalString(value: unknown): string | undefined {
  if (value === null || value === undefined || value === '') return undefined;
  return String(value);
}

function numberValue(value: unknown, fallback = 0): number {
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (typeof value === 'string' && value.trim() !== '') {
    const parsed = Number(value);
    if (Number.isFinite(parsed)) return parsed;
  }
  return fallback;
}

function moneyValue(value: unknown, fallback = '0.00'): string {
  if (value === null || value === undefined || value === '') return fallback;
  if (typeof value === 'number' && Number.isFinite(value)) return value.toFixed(2);
  const parsed = Number(value);
  if (Number.isFinite(parsed)) return parsed.toFixed(2);
  return String(value);
}

function centsToMoney(value: unknown): string {
  return (numberValue(value) / 100).toFixed(2);
}

function arrayValue(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

function firstArray(record: UnknownRecord, keys: string[]): unknown[] {
  for (const key of keys) {
    const value = record[key];
    if (Array.isArray(value)) return value;
  }
  return [];
}

function firstValue(record: UnknownRecord, keys: string[]): unknown {
  for (const key of keys) {
    const value = record[key];
    if (value !== undefined && value !== null) return value;
  }
  return undefined;
}

function normalizeStatus(value: unknown): SeatStatus {
  const status = stringValue(value, 'AVAILABLE').toUpperCase();
  if (status === 'BOOKED' || status === 'HELD') return status;
  return 'AVAILABLE';
}

function statusCounts(value: unknown): { sold: number; cancelled: number } {
  if (Array.isArray(value)) {
    return value.reduce(
      (counts, item) => {
        if (!isRecord(item)) return counts;
        const status = stringValue(firstValue(item, ['status', 'booking_status', 'bookingStatus'])).toUpperCase();
        const count = numberValue(firstValue(item, ['count', 'total', 'value']));
        if (status === 'CONFIRMED' || status === 'SOLD') counts.sold += count;
        if (status === 'CANCELLED' || status === 'CANCELED') counts.cancelled += count;
        return counts;
      },
      { sold: 0, cancelled: 0 }
    );
  }
  if (isRecord(value)) {
    return {
      sold: numberValue(firstValue(value, ['CONFIRMED', 'confirmed', 'SOLD', 'sold'])),
      cancelled: numberValue(firstValue(value, ['CANCELLED', 'CANCELED', 'cancelled', 'canceled'])),
    };
  }
  return { sold: 0, cancelled: 0 };
}

export function normalizeEventListItem(raw: unknown): EventListItem {
  const record = isRecord(raw) ? raw : {};
  return {
    id: stringValue(record.id),
    name: stringValue(record.name, 'Untitled event'),
    event_date: stringValue(firstValue(record, ['event_date', 'eventDate', 'date'])),
    venue_name: stringValue(firstValue(record, ['venue_name', 'venueName', 'venue'])),
    total_seats: numberValue(firstValue(record, ['total_seats', 'totalSeats'])),
    min_price: optionalString(firstValue(record, ['min_price', 'minPrice'])),
    max_price: optionalString(firstValue(record, ['max_price', 'maxPrice'])),
    status: stringValue(record.status, 'SCHEDULED'),
  };
}

export function normalizeEventDetail(raw: unknown): EventDetail {
  const record = isRecord(raw) ? raw : {};
  const base = normalizeEventListItem(record);
  const seatsSummary = firstValue(record, ['seats_summary', 'seatsSummary']);
  const normalizedSummary = isRecord(seatsSummary)
    ? {
        total: numberValue(firstValue(seatsSummary, ['total', 'total_seats', 'totalSeats']), base.total_seats),
        available: numberValue(seatsSummary.available),
        booked: numberValue(seatsSummary.booked),
      }
    : undefined;
  const organizer = isRecord(record.organizer) ? record.organizer : undefined;
  return {
    ...base,
    description: optionalString(record.description),
    organizer: organizer
      ? {
          id: stringValue(organizer.id),
          full_name: stringValue(firstValue(organizer, ['full_name', 'fullName', 'name'])),
        }
      : undefined,
    seats_summary: normalizedSummary,
  };
}

export function normalizeEventPage(raw: unknown): CursorPage<EventListItem> {
  if (Array.isArray(raw)) return { items: raw.map(normalizeEventListItem), next_cursor: null };
  const record = isRecord(raw) ? raw : {};
  const items = firstArray(record, ['items', 'content', 'data', 'events']);
  return {
    items: items.map(normalizeEventListItem),
    next_cursor: optionalString(firstValue(record, ['next_cursor', 'nextCursor'])) ?? null,
  };
}

function normalizeSeat(raw: unknown, rowLabel: string, booked: Set<string>, held: Set<string>): Seat {
  const record = isRecord(raw) ? raw : {};
  const id = stringValue(firstValue(record, ['id', 'seat_id', 'seatId']));
  const listedStatus = held.has(id) ? 'HELD' : booked.has(id) ? 'BOOKED' : normalizeStatus(record.status);
  const booleanStatus = record.booked === true ? 'BOOKED' : record.held === true ? 'HELD' : listedStatus;
  return {
    id,
    number: numberValue(firstValue(record, ['number', 'seat_number', 'seatNumber'])),
    tier: stringValue(record.tier, 'STANDARD'),
    price: moneyValue(record.price),
    status: normalizeStatus(booleanStatus),
  };
}

export function normalizeSeatMap(raw: unknown, fallbackEventId = ''): SeatMapResponse {
  const record = isRecord(raw) ? raw : {};
  const eventId = stringValue(firstValue(record, ['event_id', 'eventId']), fallbackEventId);
  const booked = new Set(arrayValue(firstValue(record, ['bookedSeatIds', 'booked_seat_ids'])).map((id) => String(id)));
  const held = new Set(arrayValue(firstValue(record, ['heldSeatIds', 'held_seat_ids'])).map((id) => String(id)));

  if (Array.isArray(record.rows)) {
    return {
      event_id: eventId,
      rows: record.rows.filter(isRecord).map((row) => {
        const label = stringValue(firstValue(row, ['label', 'row', 'row_label', 'rowLabel']));
        return { label, seats: arrayValue(row.seats).map((seat) => normalizeSeat(seat, label, booked, held)) };
      }),
      fetched_at: stringValue(firstValue(record, ['fetched_at', 'fetchedAt']), new Date().toISOString()),
    };
  }

  const grouped = new Map<string, Seat[]>();
  for (const seatRaw of arrayValue(record.seats)) {
    const seatRecord = isRecord(seatRaw) ? seatRaw : {};
    const label = stringValue(firstValue(seatRecord, ['row', 'row_label', 'rowLabel', 'label']), '?');
    const seats = grouped.get(label) ?? [];
    seats.push(normalizeSeat(seatRecord, label, booked, held));
    grouped.set(label, seats);
  }

  const rows: SeatRow[] = Array.from(grouped.entries()).map(([label, seats]) => ({
    label,
    seats: seats.sort((a, b) => a.number - b.number),
  }));

  return {
    event_id: eventId,
    rows,
    fetched_at: stringValue(firstValue(record, ['fetched_at', 'fetchedAt']), new Date().toISOString()),
  };
}

function findSeat(map: SeatMapResponse | undefined, seatId: string): (Seat & { row: string }) | undefined {
  return map?.rows
    .flatMap((row) => row.seats.map((seat) => ({ ...seat, row: row.label })))
    .find((seat) => seat.id === seatId);
}

function normalizeHoldSeat(raw: unknown, map?: SeatMapResponse): HoldSeat {
  const record = isRecord(raw) ? raw : {};
  const id = stringValue(firstValue(record, ['id', 'seat_id', 'seatId']));
  const mapped = findSeat(map, id);
  return {
    id,
    row: stringValue(firstValue(record, ['row', 'row_label', 'rowLabel']), mapped?.row ?? '?'),
    number: numberValue(firstValue(record, ['number', 'seat_number', 'seatNumber']), mapped?.number ?? 0),
    price: moneyValue(record.price, mapped?.price ?? '0.00'),
  };
}

export function normalizeHoldResponse(
  raw: unknown,
  context: { eventId?: string; seatIds?: string[]; seatMap?: SeatMapResponse; now?: Date } = {}
): HoldResponse {
  const record = isRecord(raw) ? raw : {};
  const ttl = numberValue(firstValue(record, ['ttl_seconds', 'ttlSeconds', 'ttl']), 600);
  const expiresValue = firstValue(record, ['expires_at', 'expiresAt', 'expires']);
  const expiresAt = expiresValue
    ? stringValue(expiresValue)
    : new Date((context.now?.getTime() ?? Date.now()) + ttl * 1000).toISOString();
  const seatsFromResponse = arrayValue(record.seats).map((seat) => normalizeHoldSeat(seat, context.seatMap));
  const seatsFromMap = (context.seatIds ?? [])
    .map((seatId) => findSeat(context.seatMap, seatId))
    .filter((seat): seat is Seat & { row: string } => Boolean(seat))
    .map((seat) => ({ id: seat.id, row: seat.row, number: seat.number, price: seat.price }));
  const seats = seatsFromResponse.length ? seatsFromResponse : seatsFromMap;
  const total = firstValue(record, ['total_amount', 'totalAmount', 'total', 'amount']);
  return {
    hold_group_id: stringValue(firstValue(record, ['hold_group_id', 'holdGroupId', 'groupId', 'id'])),
    hold_token: stringValue(firstValue(record, ['hold_token', 'holdToken', 'token'])),
    expires_at: expiresAt,
    ttl_seconds: ttl,
    seats,
    total_amount:
      total !== undefined
        ? moneyValue(total)
        : moneyValue(seats.reduce((sum, seat) => sum + numberValue(seat.price), 0)),
    event_id: optionalString(firstValue(record, ['event_id', 'eventId'])) ?? context.eventId,
  };
}

export function normalizeBookingItem(raw: unknown, defaults: Partial<BookingRecord> = {}): BookingRecord {
  const record = isRecord(raw) ? raw : {};
  const event = isRecord(record.event) ? normalizeEventListItem(record.event) : undefined;
  return {
    id: stringValue(firstValue(record, ['id', 'booking_id', 'bookingId'])),
    seat_id: stringValue(firstValue(record, ['seat_id', 'seatId'])),
    row: stringValue(firstValue(record, ['row', 'row_label', 'rowLabel'])),
    number: numberValue(firstValue(record, ['number', 'seat_number', 'seatNumber'])),
    amount: moneyValue(firstValue(record, ['amount', 'price'])),
    booking_status: stringValue(
      firstValue(record, ['booking_status', 'bookingStatus', 'status']),
      'CONFIRMED'
    ).toUpperCase() as BookingRecord['booking_status'],
    event,
    event_name: optionalString(firstValue(record, ['event_name', 'eventName'])) ?? event?.name ?? defaults.event_name,
    event_date:
      optionalString(firstValue(record, ['event_date', 'eventDate'])) ?? event?.event_date ?? defaults.event_date,
    created_at: optionalString(firstValue(record, ['created_at', 'createdAt'])) ?? defaults.created_at,
    ticket_pdf_url:
      optionalString(firstValue(record, ['ticket_pdf_url', 'ticketPdfUrl', 'ticket_url', 'ticketUrl'])) ??
      defaults.ticket_pdf_url,
    hold_group_id: optionalString(firstValue(record, ['hold_group_id', 'holdGroupId'])) ?? defaults.hold_group_id,
  };
}

export function normalizeBookingResponse(raw: unknown): BookingResponse {
  const record = isRecord(raw) ? raw : {};
  const rawBookings = Array.isArray(raw)
    ? raw
    : Array.isArray(record.bookings)
      ? record.bookings
      : raw === undefined || raw === null
        ? []
        : [raw];
  const defaults: Partial<BookingRecord> = {
    created_at: optionalString(firstValue(record, ['created_at', 'createdAt'])),
    event_name: optionalString(firstValue(record, ['event_name', 'eventName'])),
    event_date: optionalString(firstValue(record, ['event_date', 'eventDate'])),
    hold_group_id: optionalString(firstValue(record, ['hold_group_id', 'holdGroupId'])),
  };
  const bookings = rawBookings.map((item) => normalizeBookingItem(item, defaults));
  const total = firstValue(record, ['total_amount', 'totalAmount', 'total']);
  return {
    booking_group_id: optionalString(firstValue(record, ['booking_group_id', 'bookingGroupId', 'groupId'])),
    bookings,
    total_amount:
      total !== undefined
        ? moneyValue(total)
        : moneyValue(bookings.reduce((sum, booking) => sum + numberValue(booking.amount), 0)),
    created_at: stringValue(
      firstValue(record, ['created_at', 'createdAt']),
      bookings[0]?.created_at ?? new Date().toISOString()
    ),
    ticket_pdf_urls: arrayValue(firstValue(record, ['ticket_pdf_urls', 'ticketPdfUrls'])).map((url) => String(url)),
  };
}

export function normalizeBookingsPage(raw: unknown): CursorPage<BookingRecord> {
  if (Array.isArray(raw))
    return { items: raw.flatMap((item) => normalizeBookingResponse(item).bookings), next_cursor: null };
  const record = isRecord(raw) ? raw : {};
  const pageKeys = ['items', 'content', 'data', 'bookings'];
  const pageKey = pageKeys.find((key) => Array.isArray(record[key]));
  const source = pageKey ? arrayValue(record[pageKey]) : [];
  const items = pageKey
    ? source.flatMap((item) => normalizeBookingResponse(item).bookings)
    : normalizeBookingResponse(raw).bookings;
  return { items, next_cursor: optionalString(firstValue(record, ['next_cursor', 'nextCursor'])) ?? null };
}

function normalizeTier(raw: unknown): { tier: string; total: number; sold: number; revenue: string } {
  const record = isRecord(raw) ? raw : {};
  return {
    tier: stringValue(record.tier, 'UNKNOWN'),
    total: numberValue(record.total),
    sold: numberValue(firstValue(record, ['sold', 'ticketsSold', 'tickets_sold'])),
    revenue: record.revenueCents !== undefined ? centsToMoney(record.revenueCents) : moneyValue(record.revenue),
  };
}

function normalizeDaily(raw: unknown): { date: string; tickets_sold: number; revenue: string } {
  const record = isRecord(raw) ? raw : {};
  return {
    date: stringValue(firstValue(record, ['date', 'sales_date', 'salesDate'])),
    tickets_sold: numberValue(firstValue(record, ['tickets_sold', 'ticketsSold', 'sold'])),
    revenue: record.revenueCents !== undefined ? centsToMoney(record.revenueCents) : moneyValue(record.revenue),
  };
}

export function normalizeDashboard(raw: unknown, fallbackEventId = ''): Dashboard {
  const record = isRecord(raw) ? raw : {};
  const byStatus = statusCounts(record.byStatus);
  const totalSeats = numberValue(firstValue(record, ['total_seats', 'totalSeats']));
  const sold = numberValue(record.sold, numberValue(record.ticketsSold, byStatus.sold));
  const cancelled = numberValue(record.cancelled, numberValue(record.ticketsCancelled, byStatus.cancelled));
  const available = numberValue(record.available, Math.max(0, totalSeats - sold));
  const revenueRecord = isRecord(record.revenue) ? record.revenue : undefined;
  const gross = revenueRecord
    ? moneyValue(revenueRecord.gross)
    : record.revenueCents !== undefined
      ? centsToMoney(record.revenueCents)
      : moneyValue(record.revenue);
  const refunded = revenueRecord
    ? moneyValue(revenueRecord.refunded)
    : record.refundedCents !== undefined
      ? centsToMoney(record.refundedCents)
      : moneyValue(record.refunded);
  const net = revenueRecord ? moneyValue(revenueRecord.net) : moneyValue(numberValue(gross) - numberValue(refunded));
  return {
    event_id: stringValue(firstValue(record, ['event_id', 'eventId']), fallbackEventId),
    event_name: stringValue(firstValue(record, ['event_name', 'eventName', 'name']), 'Event'),
    event_date: stringValue(firstValue(record, ['event_date', 'eventDate'])),
    total_seats: totalSeats,
    sold,
    cancelled,
    available,
    occupancy_pct: numberValue(
      firstValue(record, ['occupancy_pct', 'occupancyPct']),
      totalSeats ? (sold / totalSeats) * 100 : 0
    ),
    revenue: { gross, refunded, net },
    by_tier: firstArray(record, ['by_tier', 'byTier', 'tiers']).map(normalizeTier),
    daily_sales_last_30d: firstArray(record, ['daily_sales_last_30d', 'dailySalesLast30d', 'dailySales']).map(
      normalizeDaily
    ),
  };
}
