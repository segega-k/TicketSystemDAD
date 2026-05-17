import { useCallback, useEffect, useState } from 'react';
import { ErrorBanner } from '../components/ErrorBanner';
import { Loading } from '../components/Loading';
import { bookingsApi, downloadTicketPdf, problemMessage } from '../lib/api';
import { formatDateTime, money } from '../lib/format';
import type { BookingRecord } from '../types';

function normalizeItems(items: unknown[]): BookingRecord[] {
  const normalized: BookingRecord[] = [];
  for (const item of items) {
    const value = item as { bookings?: BookingRecord[]; created_at?: string } & BookingRecord;
    if (Array.isArray(value.bookings)) {
      normalized.push(
        ...value.bookings.map((booking) => ({ ...booking, created_at: value.created_at ?? booking.created_at }))
      );
    } else {
      normalized.push(value);
    }
  }
  return normalized;
}

export function MyBookingsPage() {
  const [items, setItems] = useState<BookingRecord[]>([]);
  const [cursor, setCursor] = useState<string | undefined>();
  const [history, setHistory] = useState<string[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null | undefined>();
  const [loading, setLoading] = useState(true);
  const [downloadingId, setDownloadingId] = useState<string | null>(null);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const page = await bookingsApi.me({ cursor, limit: 20 });
      setItems(normalizeItems(page.items));
      setNextCursor(page.next_cursor);
    } catch (err) {
      setError(problemMessage(err));
    } finally {
      setLoading(false);
    }
  }, [cursor]);

  useEffect(() => {
    void load();
  }, [load]);

  function next() {
    if (nextCursor) {
      setHistory((h) => [...h, cursor ?? '']);
      setCursor(nextCursor);
    }
  }
  function prev() {
    setHistory((h) => {
      const copy = [...h];
      const previous = copy.pop();
      setCursor(previous || undefined);
      return copy;
    });
  }
  async function cancel(id: string) {
    const reason = window.prompt('Cancellation reason', 'Changed plans');
    if (!reason) return;
    try {
      await bookingsApi.cancel(id, reason);
      await load();
    } catch (err) {
      setError(problemMessage(err));
    }
  }
  async function downloadTicket(id: string) {
    setDownloadingId(id);
    setError('');
    try {
      await downloadTicketPdf(id);
    } catch (err) {
      setError(problemMessage(err));
    } finally {
      setDownloadingId(null);
    }
  }

  return (
    <div className="space-y-5">
      <div>
        <h1 className="text-3xl font-bold">My bookings</h1>
        <p className="mt-1 text-slate-600">Download PDF tickets or cancel eligible confirmed bookings.</p>
      </div>
      <ErrorBanner message={error} />
      {loading ? (
        <Loading label="Loading bookings" />
      ) : (
        <div className="space-y-3">
          {items.map((booking) => (
            <article
              className="card flex flex-col gap-4 md:flex-row md:items-center md:justify-between"
              key={booking.id}
            >
              <div>
                <h2 className="font-bold">
                  {booking.event_name ?? booking.event?.name ?? 'Booking'} · Row {booking.row} seat {booking.number}
                </h2>
                <p className="text-sm text-slate-600">
                  {formatDateTime(booking.created_at)}{' '}
                  {booking.event_date && `· Event ${formatDateTime(booking.event_date)}`}
                </p>
                <p className="mt-1 text-sm">
                  {money(booking.amount)}{' '}
                  <span
                    className={`badge ml-2 ${booking.booking_status === 'CANCELLED' ? 'bg-slate-100 text-slate-600' : 'bg-emerald-50 text-emerald-700'}`}
                  >
                    {booking.booking_status ?? 'CONFIRMED'}
                  </span>
                </p>
              </div>
              <div className="flex flex-wrap gap-2">
                <button
                  className="btn btn-secondary"
                  disabled={downloadingId === booking.id}
                  onClick={() => downloadTicket(booking.id)}
                >
                  {downloadingId === booking.id ? 'Downloading…' : 'PDF ticket'}
                </button>
                <button
                  className="btn btn-danger"
                  disabled={booking.booking_status === 'CANCELLED'}
                  onClick={() => cancel(booking.id)}
                >
                  Cancel
                </button>
              </div>
            </article>
          ))}
          {!items.length && <p className="card">No bookings yet.</p>}
        </div>
      )}
      <div className="flex items-center justify-between">
        <button className="btn btn-secondary" disabled={!history.length} onClick={prev}>
          Previous
        </button>
        <button className="btn btn-secondary" disabled={!nextCursor} onClick={next}>
          Next
        </button>
      </div>
    </div>
  );
}
