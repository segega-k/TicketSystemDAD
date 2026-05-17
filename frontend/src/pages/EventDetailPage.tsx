import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ErrorBanner } from '../components/ErrorBanner';
import { Loading } from '../components/Loading';
import { SeatMap } from '../components/SeatMap';
import { eventsApi, holdsApi, organizerApi, problemMessage } from '../lib/api';
import { validateAdjacentSelection, getSelectedSeats } from '../lib/adjacentSeats';
import { useAuthStore } from '../lib/authStore';
import { useHoldStore } from '../lib/holdStore';
import { formatDateTime, money } from '../lib/format';
import { subscribeSeatUpdates } from '../lib/ws';
import type { EventDetail, SeatMapResponse } from '../types';

export function EventDetailPage() {
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const authed = useAuthStore((s) => s.isAuthenticated());
  const role = useAuthStore((s) => s.user?.role);
  const currentUserId = useAuthStore((s) => s.user?.id);
  const setHold = useHoldStore((s) => s.setHold);
  const [event, setEvent] = useState<EventDetail | null>(null);
  const [map, setMap] = useState<SeatMapResponse | null>(null);
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [holding, setHolding] = useState(false);
  const [error, setError] = useState('');
  const [liveStatus, setLiveStatus] = useState('');

  const loadAll = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [detail, seats] = await Promise.all([eventsApi.detail(id), eventsApi.seats(id)]);
      setEvent(detail);
      setMap(seats);
    } catch (err) {
      setError(problemMessage(err));
    } finally {
      setLoading(false);
    }
  }, [id]);

  const loadSeatsOnly = useCallback(async () => {
    try {
      setMap(await eventsApi.seats(id));
    } catch (err) {
      setLiveStatus(problemMessage(err));
    }
  }, [id]);

  useEffect(() => {
    void loadAll();
  }, [loadAll]);
  useEffect(
    () =>
      subscribeSeatUpdates(
        id,
        () => {
          void loadSeatsOnly();
        },
        setLiveStatus
      ),
    [id, loadSeatsOnly]
  );

  const validation = useMemo(() => validateAdjacentSelection(map, selectedIds), [map, selectedIds]);
  const selectedSeats = useMemo(() => getSelectedSeats(map, selectedIds), [map, selectedIds]);
  const selectedTotal = selectedSeats.reduce((sum, seat) => sum + Number(seat.price), 0);

  function toggleSeat(seatId: string) {
    setSelectedIds((current) =>
      current.includes(seatId) ? current.filter((id) => id !== seatId) : [...current, seatId].slice(0, 6)
    );
  }
  async function holdSeats() {
    if (!authed) {
      navigate(`/login?next=${encodeURIComponent(`/events/${id}`)}`);
      return;
    }
    if (!validation.ok) return;
    setHolding(true);
    setError('');
    try {
      const hold = await holdsApi.hold(id, selectedIds);
      setHold({ ...hold, event_id: id });
      navigate(`/checkout/${hold.hold_group_id}`);
    } catch (err) {
      setError(problemMessage(err));
      await loadSeatsOnly();
    } finally {
      setHolding(false);
    }
  }

  if (loading) return <Loading label="Loading event" />;
  if (!event || !map) return <ErrorBanner message={error || 'Event not found'} />;
  return (
    <div className="space-y-6">
      <div className="grid gap-5 lg:grid-cols-[1fr_22rem]">
        <section className="card">
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <span className="badge bg-blue-50 text-blue-700">{event.status}</span>
              <h1 className="mt-3 text-3xl font-bold">{event.name}</h1>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              {(role === 'ORGANIZER' || role === 'ADMIN' || role === 'ANALYST') && (
                <Link className="btn btn-secondary" to={`/organizer/events/${event.id}/dashboard`}>
                  View analytics
                </Link>
              )}
              {(role === 'ADMIN' || (role === 'ORGANIZER' && event.organizer?.id === currentUserId)) && (
                <button
                  className="btn px-3 py-2 text-sm text-rose-700 ring-1 ring-rose-300 hover:bg-rose-50"
                  onClick={async () => {
                    if (
                      !confirm(
                        'Delete this event? All confirmed bookings will be cancelled and refunded automatically.'
                      )
                    )
                      return;
                    try {
                      await organizerApi.deleteEvent(event.id);
                      navigate('/events');
                    } catch (err) {
                      setError(problemMessage(err));
                    }
                  }}
                >
                  Delete event
                </button>
              )}
            </div>
          </div>
          <p className="mt-3 text-slate-700">{event.description}</p>
          <dl className="mt-5 grid gap-3 text-sm sm:grid-cols-3">
            <div>
              <dt className="font-semibold">Date</dt>
              <dd>{formatDateTime(event.event_date)}</dd>
            </div>
            <div>
              <dt className="font-semibold">Venue</dt>
              <dd>{event.venue_name}</dd>
            </div>
            <div>
              <dt className="font-semibold">Availability</dt>
              <dd>
                {event.seats_summary?.available ?? '—'} / {event.seats_summary?.total ?? event.total_seats}
              </dd>
            </div>
          </dl>
        </section>
        <aside className="card">
          <h2 className="text-lg font-bold">Selection</h2>
          <p className="mt-2 text-sm text-slate-600">Choose 1–6 adjacent available seats in a single row.</p>
          <ul className="mt-4 space-y-1 text-sm">
            {selectedSeats.map((seat) => (
              <li key={seat.id}>
                Row {seat.row} seat {seat.number} · {money(seat.price)}
              </li>
            ))}
            {!selectedSeats.length && <li>No seats selected.</li>}
          </ul>
          <p className="mt-4 font-bold">Total: {money(selectedTotal)}</p>
          {!validation.ok && selectedIds.length > 0 && (
            <p className="mt-2 text-sm text-rose-700">{validation.message}</p>
          )}
          <button className="btn btn-primary mt-4 w-full" disabled={!validation.ok || holding} onClick={holdSeats}>
            {holding ? 'Holding…' : 'Hold seats'}
          </button>
          <p className="mt-3 text-xs text-slate-500">
            Holds expire after 10 minutes. Checkout uses an Idempotency-Key.
          </p>
        </aside>
      </div>
      <ErrorBanner message={error} />
      {liveStatus && (
        <p className="text-sm text-slate-600" aria-live="polite">
          {liveStatus}
        </p>
      )}
      <SeatMap map={map} selectedIds={selectedIds} onToggle={toggleSeat} />
    </div>
  );
}
