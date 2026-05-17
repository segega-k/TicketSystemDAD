import { useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ErrorBanner } from '../components/ErrorBanner';
import { bookingsApi, downloadTicketPdf, holdsApi, problemMessage } from '../lib/api';
import { makeIdempotencyKey, money } from '../lib/format';
import { useHoldStore } from '../lib/holdStore';
import type { BookingResponse } from '../types';

function formatRemaining(ms: number): string {
  const totalSeconds = Math.max(0, Math.ceil(ms / 1000));
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${seconds.toString().padStart(2, '0')}`;
}

export function CheckoutPage() {
  const { holdGroupId = '' } = useParams();
  const hold = useHoldStore((s) => s.holds[holdGroupId]);
  const removeHold = useHoldStore((s) => s.removeHold);
  const clearExpired = useHoldStore((s) => s.clearExpired);
  const [paymentToken, setPaymentToken] = useState('MOCK_PAY_OK');
  const [idempotencyKey] = useState(() => makeIdempotencyKey());
  const [now, setNow] = useState(() => Date.now());
  const [submitting, setSubmitting] = useState(false);
  const [releasing, setReleasing] = useState(false);
  const [downloadingId, setDownloadingId] = useState<string | null>(null);
  const [error, setError] = useState('');
  const [booking, setBooking] = useState<BookingResponse | null>(null);

  useEffect(() => {
    clearExpired();
  }, [clearExpired]);
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);

  const expiresAtMs = useMemo(() => (hold ? new Date(hold.expires_at).getTime() : 0), [hold]);
  const remainingMs = Math.max(0, expiresAtMs - now);
  const expired = Boolean(hold && remainingMs <= 0);

  async function checkout() {
    if (!hold || expired) return;
    setSubmitting(true);
    setError('');
    try {
      const result = await bookingsApi.checkout(hold.hold_group_id, hold.hold_token, paymentToken, idempotencyKey);
      setBooking(result);
      removeHold(hold.hold_group_id);
    } catch (err) {
      setError(problemMessage(err));
    } finally {
      setSubmitting(false);
    }
  }
  async function release() {
    if (!hold) return;
    setReleasing(true);
    setError('');
    try {
      await holdsApi.release(hold.hold_group_id);
      removeHold(hold.hold_group_id);
    } catch (err) {
      setError(problemMessage(err));
    } finally {
      setReleasing(false);
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

  if (booking)
    return (
      <section className="mx-auto max-w-2xl card">
        <h1 className="text-2xl font-bold">Booking confirmed</h1>
        <p className="mt-2 text-slate-600">Total paid: {money(booking.total_amount)}</p>
        <ErrorBanner message={error} />
        <ul className="mt-4 space-y-2">
          {booking.bookings.map((item) => (
            <li className="flex flex-wrap items-center justify-between gap-3 rounded-xl bg-slate-50 p-3" key={item.id}>
              <span>
                Row {item.row} seat {item.number} · {money(item.amount)}
              </span>
              <button
                className="btn btn-secondary"
                disabled={downloadingId === item.id}
                onClick={() => downloadTicket(item.id)}
              >
                {downloadingId === item.id ? 'Downloading…' : 'Download PDF ticket'}
              </button>
            </li>
          ))}
        </ul>
        <Link className="btn btn-primary mt-5" to="/me/bookings">
          Go to my bookings
        </Link>
      </section>
    );
  if (!hold)
    return (
      <section className="card">
        <h1 className="text-2xl font-bold">Hold not found</h1>
        <p className="mt-2 text-slate-600">
          The hold may have expired or this checkout was opened in a different browser tab.
        </p>
        <Link className="btn btn-primary mt-4" to="/events">
          Find events
        </Link>
      </section>
    );
  return (
    <section className="mx-auto max-w-2xl card">
      <h1 className="text-2xl font-bold">Checkout</h1>
      <ErrorBanner message={error} />
      <div className={`mt-5 rounded-xl p-4 ${expired ? 'bg-rose-50' : 'bg-slate-50'}`}>
        <p className="text-sm text-slate-600">Hold group</p>
        <p className="font-mono text-sm">{hold.hold_group_id}</p>
        <p className="mt-3 text-sm text-slate-600">Expires at {new Date(hold.expires_at).toLocaleTimeString()}</p>
        <p className={`mt-2 text-lg font-bold ${expired ? 'text-rose-700' : 'text-slate-900'}`} aria-live="polite">
          {expired
            ? 'This hold has expired. Please select seats again.'
            : `Time remaining: ${formatRemaining(remainingMs)}`}
        </p>
        {remainingMs > 0 && remainingMs <= 60000 && (
          <p className="mt-1 text-sm text-amber-700">Checkout soon — your hold expires in under a minute.</p>
        )}
      </div>
      <ul className="mt-5 space-y-2">
        {hold.seats.map((seat) => (
          <li className="flex justify-between rounded-xl border border-slate-200 p-3" key={seat.id}>
            <span>
              Row {seat.row} seat {seat.number}
            </span>
            <strong>{money(seat.price)}</strong>
          </li>
        ))}
      </ul>
      <p className="mt-4 text-right text-xl font-bold">{money(hold.total_amount)}</p>
      <label className="mt-5 block">
        <span className="label">Mock payment outcome</span>
        <select
          className="input mt-1"
          value={paymentToken}
          onChange={(e) => setPaymentToken(e.target.value)}
          disabled={expired}
        >
          <option value="MOCK_PAY_OK">Approve</option>
          <option value="MOCK_PAY_DECLINED">Decline</option>
        </select>
      </label>
      <p className="mt-3 break-all text-xs text-slate-500">Idempotency-Key: {idempotencyKey}</p>
      <div className="mt-5 flex flex-wrap gap-3">
        <button className="btn btn-primary" disabled={expired || submitting} onClick={checkout}>
          {submitting ? 'Confirming…' : 'Confirm booking'}
        </button>
        <button className="btn btn-secondary" disabled={releasing} onClick={release}>
          {releasing ? 'Releasing…' : 'Release hold'}
        </button>
        {expired && (
          <Link className="btn btn-primary" to={hold.event_id ? `/events/${hold.event_id}` : '/events'}>
            Select seats again
          </Link>
        )}
      </div>
    </section>
  );
}
