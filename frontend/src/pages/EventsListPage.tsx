import { FormEvent, useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ErrorBanner } from '../components/ErrorBanner';
import { Loading } from '../components/Loading';
import { eventsApi, problemMessage } from '../lib/api';
import { formatDateTime, money } from '../lib/format';
import type { EventListItem } from '../types';

export function EventsListPage() {
  const [events, setEvents] = useState<EventListItem[]>([]);
  const [q, setQ] = useState('');
  const [activeQ, setActiveQ] = useState('');
  const [cursor, setCursor] = useState<string | undefined>();
  const [history, setHistory] = useState<string[]>([]);
  const [nextCursor, setNextCursor] = useState<string | undefined | null>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const page = await eventsApi.list({ q: activeQ || undefined, cursor, limit: 12 });
      setEvents(page.items);
      setNextCursor(page.next_cursor);
    } catch (err) {
      setError(problemMessage(err));
    } finally {
      setLoading(false);
    }
  }, [activeQ, cursor]);

  useEffect(() => {
    void load();
  }, [load]);

  function submit(event: FormEvent) {
    event.preventDefault();
    setHistory([]);
    setCursor(undefined);
    setActiveQ(q.trim());
  }
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

  return (
    <div className="space-y-6">
      <div className="flex flex-col gap-4 md:flex-row md:items-end md:justify-between">
        <div>
          <h1 className="text-3xl font-bold">Events</h1>
          <p className="mt-1 text-slate-600">Search upcoming events with cursor pagination.</p>
        </div>
        <form onSubmit={submit} className="flex w-full gap-2 md:max-w-md">
          <label className="sr-only" htmlFor="q">
            Search events
          </label>
          <input
            id="q"
            className="input"
            placeholder="Search by name…"
            value={q}
            onChange={(e) => setQ(e.target.value)}
          />
          <button className="btn btn-primary">Search</button>
        </form>
      </div>
      <ErrorBanner message={error} />
      {loading ? (
        <Loading label="Loading events" />
      ) : (
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          {events.map((event) => (
            <article className="card flex flex-col" key={event.id}>
              <div className="flex-1">
                <span className="badge bg-blue-50 text-blue-700">{event.status}</span>
                <h2 className="mt-3 text-xl font-bold">
                  <Link to={`/events/${event.id}`}>{event.name}</Link>
                </h2>
                <p className="mt-2 text-sm text-slate-600">{event.venue_name}</p>
                <p className="mt-1 text-sm text-slate-600">{formatDateTime(event.event_date)}</p>
                <p className="mt-3 text-sm font-semibold">
                  {event.total_seats} seats · {money(event.min_price)}–{money(event.max_price)}
                </p>
              </div>
              <Link className="btn btn-secondary mt-5" to={`/events/${event.id}`}>
                View seats
              </Link>
            </article>
          ))}
          {!events.length && <p className="card md:col-span-2 xl:col-span-3">No events found.</p>}
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
