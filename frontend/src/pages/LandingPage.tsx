import { Link } from 'react-router-dom';

export function LandingPage() {
  return (
    <div className="grid gap-8 lg:grid-cols-[1.2fr_0.8fr] lg:items-center">
      <section className="card bg-gradient-to-br from-slate-900 to-brand-700 p-8 text-white">
        <p className="text-sm font-semibold uppercase tracking-widest text-blue-100">Flash-sale ready booking</p>
        <h1 className="mt-4 text-4xl font-bold tracking-tight sm:text-5xl">
          Find events, hold adjacent seats, and download QR tickets.
        </h1>
        <p className="mt-5 max-w-2xl text-lg text-blue-50">
          Browse public events, select 1–6 adjacent seats on a live seat map, complete mock checkout with idempotent
          booking, and manage your tickets.
        </p>
        <div className="mt-8 flex flex-wrap gap-3">
          <Link className="btn bg-white text-slate-900 hover:bg-blue-50" to="/events">
            Browse events
          </Link>
          <Link className="btn border border-white/50 text-white hover:bg-white/10" to="/register">
            Create account
          </Link>
        </div>
      </section>
      <aside className="grid gap-4">
        {[
          'Live STOMP seat updates when signed in',
          'Atomic holds with 10-minute countdown',
          'Booking history, cancellation, and PDF tickets',
        ].map((item) => (
          <div className="card" key={item}>
            <p className="font-semibold">{item}</p>
          </div>
        ))}
      </aside>
    </div>
  );
}
