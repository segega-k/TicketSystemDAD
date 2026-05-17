import type { SeatMapResponse, SeatStatus } from '../types';

const statusClass: Record<SeatStatus, string> = {
  AVAILABLE: 'border-emerald-300 bg-emerald-50 text-emerald-900 hover:bg-emerald-100',
  HELD: 'border-amber-300 bg-amber-50 text-amber-900 cursor-not-allowed',
  BOOKED: 'border-slate-300 bg-slate-200 text-slate-500 cursor-not-allowed',
};

export function SeatMap({
  map,
  selectedIds,
  onToggle,
}: {
  map: SeatMapResponse;
  selectedIds: string[];
  onToggle: (seatId: string) => void;
}) {
  return (
    <section aria-labelledby="seat-map-title" className="card">
      <div className="mb-5 rounded-xl bg-slate-900 px-4 py-3 text-center text-sm font-semibold uppercase tracking-wider text-white">
        Stage
      </div>
      <h2 id="seat-map-title" className="sr-only">
        Seat map
      </h2>
      <div className="space-y-3 overflow-x-auto pb-2">
        {map.rows.map((row) => (
          <div key={row.label} className="flex min-w-max items-center gap-2">
            <span className="w-8 text-sm font-bold text-slate-500" aria-hidden>
              {row.label}
            </span>
            {row.seats.map((seat) => {
              const selected = selectedIds.includes(seat.id);
              const disabled = seat.status !== 'AVAILABLE';
              return (
                <button
                  key={seat.id}
                  type="button"
                  disabled={disabled}
                  aria-pressed={selected}
                  aria-label={`Row ${row.label} seat ${seat.number}, ${seat.status.toLowerCase()}, ${seat.tier}, ${seat.price}`}
                  title={`${row.label}${seat.number} · ${seat.tier} · ${seat.price} · ${seat.status}`}
                  onClick={() => onToggle(seat.id)}
                  className={`h-10 w-10 rounded-lg border text-xs font-bold transition ${statusClass[seat.status]} ${selected ? 'ring-2 ring-brand-600 ring-offset-2' : ''}`}
                >
                  {seat.number}
                </button>
              );
            })}
          </div>
        ))}
      </div>
      <div className="mt-5 flex flex-wrap gap-3 text-xs text-slate-600">
        <span>
          <span className="mr-1 inline-block h-3 w-3 rounded bg-emerald-100 ring-1 ring-emerald-300" />
          Available
        </span>
        <span>
          <span className="mr-1 inline-block h-3 w-3 rounded bg-amber-100 ring-1 ring-amber-300" />
          Held
        </span>
        <span>
          <span className="mr-1 inline-block h-3 w-3 rounded bg-slate-200 ring-1 ring-slate-300" />
          Booked
        </span>
      </div>
    </section>
  );
}
