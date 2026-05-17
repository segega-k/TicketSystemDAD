import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ErrorBanner } from '../components/ErrorBanner';
import { organizerApi, problemMessage } from '../lib/api';
import type { CreateEventRowConfig } from '../types';

type DraftRow = CreateEventRowConfig;

const defaultRows: DraftRow[] = [
  { label: 'A', seat_count: 12, tier: 'VIP', price: '150.00' },
  { label: 'B', seat_count: 20, tier: 'STANDARD', price: '75.00' },
  { label: 'C', seat_count: 24, tier: 'ECONOMY', price: '45.00' },
];

function toIsoDateTime(value: string): string {
  return new Date(value).toISOString();
}

function nextRowLabel(rows: DraftRow[]): string {
  const last = rows.at(-1)?.label ?? '@';
  return String.fromCharCode(Math.min(last.charCodeAt(0) + 1, 90));
}

export function OrganizerCreateEventPage() {
  const navigate = useNavigate();
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [eventDate, setEventDate] = useState('');
  const [venueName, setVenueName] = useState('');
  const [rows, setRows] = useState<DraftRow[]>(defaultRows);
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const totalSeats = useMemo(() => rows.reduce((sum, row) => sum + Number(row.seat_count || 0), 0), [rows]);

  function updateRow(index: number, patch: Partial<DraftRow>) {
    setRows((current) => current.map((row, rowIndex) => (rowIndex === index ? { ...row, ...patch } : row)));
  }

  function addRow() {
    setRows((current) => [
      ...current,
      { label: nextRowLabel(current), seat_count: 20, tier: 'STANDARD', price: '50.00' },
    ]);
  }

  function removeRow(index: number) {
    setRows((current) => current.filter((_, rowIndex) => rowIndex !== index));
  }

  async function onSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError('');
    if (!name.trim() || !eventDate || !venueName.trim()) {
      setError('Name, date/time, and venue are required.');
      return;
    }
    const cleanedRows = rows
      .map((row) => ({
        ...row,
        label: row.label.trim().toUpperCase(),
        seat_count: Number(row.seat_count),
        price: Number(row.price).toFixed(2),
        tier: row.tier.trim().toUpperCase(),
      }))
      .filter((row) => row.label && row.seat_count > 0 && Number(row.price) >= 0);
    if (!cleanedRows.length) {
      setError('Add at least one valid row configuration.');
      return;
    }
    setSubmitting(true);
    try {
      const created = await organizerApi.createEvent({
        name: name.trim(),
        description: description.trim() || undefined,
        event_date: toIsoDateTime(eventDate),
        venue_name: venueName.trim(),
        rows: cleanedRows,
      });
      navigate(`/organizer/events/${created.id}/dashboard`, { replace: true });
    } catch (err) {
      setError(problemMessage(err));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <section className="mx-auto max-w-5xl card">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-3xl font-bold">Create event</h1>
          <p className="mt-1 text-slate-600">Configure event details and seat rows before publishing to customers.</p>
        </div>
        <span className="badge bg-blue-50 text-blue-700">{totalSeats} seats</span>
      </div>
      <ErrorBanner message={error} />
      <form className="mt-6 space-y-6" onSubmit={onSubmit}>
        <div className="grid gap-4 md:grid-cols-2">
          <label>
            <span className="label">Event name</span>
            <input className="input mt-1" value={name} onChange={(event) => setName(event.target.value)} required />
          </label>
          <label>
            <span className="label">Venue</span>
            <input
              className="input mt-1"
              value={venueName}
              onChange={(event) => setVenueName(event.target.value)}
              required
            />
          </label>
          <label>
            <span className="label">Date and time</span>
            <input
              className="input mt-1"
              type="datetime-local"
              value={eventDate}
              onChange={(event) => setEventDate(event.target.value)}
              required
            />
          </label>
        </div>
        <label className="block">
          <span className="label">Description</span>
          <textarea
            className="input mt-1 min-h-28"
            value={description}
            onChange={(event) => setDescription(event.target.value)}
          />
        </label>
        <section aria-labelledby="rows-title" className="rounded-2xl border border-slate-200 p-4">
          <div className="flex items-center justify-between gap-3">
            <h2 id="rows-title" className="text-lg font-bold">
              Rows config
            </h2>
            <button type="button" className="btn btn-secondary" onClick={addRow}>
              Add row
            </button>
          </div>
          <div className="mt-4 space-y-3">
            {rows.map((row, index) => (
              <div
                className="grid gap-3 rounded-xl bg-slate-50 p-3 md:grid-cols-[7rem_1fr_1fr_1fr_auto]"
                key={`${row.label}-${index}`}
              >
                <label>
                  <span className="label">Row</span>
                  <input
                    className="input mt-1 uppercase"
                    value={row.label}
                    maxLength={3}
                    onChange={(event) => updateRow(index, { label: event.target.value })}
                    required
                  />
                </label>
                <label>
                  <span className="label">Seats</span>
                  <input
                    className="input mt-1"
                    type="number"
                    min={1}
                    max={100}
                    value={row.seat_count}
                    onChange={(event) => updateRow(index, { seat_count: Number(event.target.value) })}
                    required
                  />
                </label>
                <label>
                  <span className="label">Tier</span>
                  <select
                    className="input mt-1"
                    value={row.tier}
                    onChange={(event) => updateRow(index, { tier: event.target.value })}
                  >
                    <option value="VIP">VIP</option>
                    <option value="STANDARD">Standard</option>
                    <option value="ECONOMY">Economy</option>
                  </select>
                </label>
                <label>
                  <span className="label">Price</span>
                  <input
                    className="input mt-1"
                    type="number"
                    min={0}
                    step="0.01"
                    value={row.price}
                    onChange={(event) => updateRow(index, { price: event.target.value })}
                    required
                  />
                </label>
                <button
                  type="button"
                  className="btn btn-danger self-end"
                  disabled={rows.length === 1}
                  onClick={() => removeRow(index)}
                >
                  Remove
                </button>
              </div>
            ))}
          </div>
        </section>
        <div className="flex flex-wrap gap-3">
          <button className="btn btn-primary" disabled={submitting}>
            {submitting ? 'Creating…' : 'Create event'}
          </button>
          <button type="button" className="btn btn-secondary" onClick={() => navigate('/events')}>
            Cancel
          </button>
        </div>
      </form>
    </section>
  );
}
