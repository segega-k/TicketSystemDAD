export type Role = 'CUSTOMER' | 'ORGANIZER' | 'ANALYST' | 'ADMIN';

export interface AuthTokens {
  access_token: string;
  refresh_token: string;
  token_type: string;
  expires_in: number;
}
export interface AuthUser {
  sub?: string;
  email?: string;
  role?: Role;
  exp?: number;
  full_name?: string;
}
export interface RegisterRequest {
  username: string;
  email: string;
  password: string;
  full_name: string;
}
export interface EventListItem {
  id: string;
  name: string;
  event_date: string;
  venue_name: string;
  total_seats: number;
  min_price?: string;
  max_price?: string;
  status: string;
}
export interface EventDetail extends EventListItem {
  description?: string;
  organizer?: { id: string; full_name: string };
  seats_summary?: { total: number; available: number; booked: number };
}
export interface CreateEventRowConfig {
  label: string;
  seat_count: number;
  tier: string;
  price: string;
}
export interface CreateEventRequest {
  name: string;
  description?: string;
  event_date: string;
  venue_name: string;
  rows: CreateEventRowConfig[];
}
export interface CursorPage<T> {
  items: T[];
  next_cursor?: string | null;
}
export type SeatStatus = 'AVAILABLE' | 'HELD' | 'BOOKED';
export interface Seat {
  id: string;
  number: number;
  tier: 'VIP' | 'STANDARD' | 'ECONOMY' | string;
  price: string;
  status: SeatStatus;
}
export interface SeatRow {
  label: string;
  seats: Seat[];
}
export interface SeatMapResponse {
  event_id: string;
  rows: SeatRow[];
  fetched_at: string;
}
export interface HoldSeat {
  id: string;
  row: string;
  number: number;
  price: string;
}
export interface HoldResponse {
  hold_group_id: string;
  hold_token: string;
  expires_at: string;
  ttl_seconds: number;
  seats: HoldSeat[];
  total_amount: string;
  event_id?: string;
}
export interface BookingItem {
  id: string;
  seat_id: string;
  row: string;
  number: number;
  amount: string;
  booking_status?: 'CONFIRMED' | 'CANCELLED';
}
export interface BookingResponse {
  booking_group_id?: string;
  bookings: BookingItem[];
  total_amount: string;
  created_at: string;
  ticket_pdf_urls?: string[];
}
export interface BookingRecord extends BookingItem {
  event?: EventListItem;
  event_name?: string;
  event_date?: string;
  created_at?: string;
  ticket_pdf_url?: string;
  hold_group_id?: string;
}
export interface CancelResponse {
  booking_id: string;
  booking_status: 'CANCELLED';
  cancelled_at: string;
  refund: { id: string; amount: string; refunded_at: string };
}
export interface Dashboard {
  event_id: string;
  event_name: string;
  event_date: string;
  total_seats: number;
  sold: number;
  cancelled: number;
  available: number;
  occupancy_pct: number;
  revenue: { gross: string; refunded: string; net: string };
  by_tier: Array<{ tier: string; total: number; sold: number; revenue: string }>;
  daily_sales_last_30d: Array<{ date: string; tickets_sold: number; revenue: string }>;
}
export interface ProblemDetails {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  trace_id?: string;
  retry_after_seconds?: number;
  conflicting_seat_ids?: string[];
  [key: string]: unknown;
}
