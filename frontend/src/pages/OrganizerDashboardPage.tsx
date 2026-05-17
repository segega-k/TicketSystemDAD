import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { ErrorBanner } from '../components/ErrorBanner';
import { Loading } from '../components/Loading';
import { organizerApi, problemMessage } from '../lib/api';
import { formatDateTime, money } from '../lib/format';
import type { Dashboard } from '../types';

export function OrganizerDashboardPage() {
  const { id = '' } = useParams();
  const [dashboard, setDashboard] = useState<Dashboard | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  useEffect(() => {
    async function load() {
      setLoading(true);
      setError('');
      try {
        setDashboard(await organizerApi.dashboard(id));
      } catch (err) {
        setError(problemMessage(err));
      } finally {
        setLoading(false);
      }
    }
    void load();
  }, [id]);
  if (loading) return <Loading label="Loading dashboard" />;
  if (!dashboard) return <ErrorBanner message={error || 'Dashboard unavailable'} />;
  const maxDaily = Math.max(1, ...dashboard.daily_sales_last_30d.map((d) => d.tickets_sold));
  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-3xl font-bold">{dashboard.event_name} dashboard</h1>
        <p className="mt-1 text-slate-600">{formatDateTime(dashboard.event_date)}</p>
      </div>
      <ErrorBanner message={error} />
      <section className="grid gap-4 md:grid-cols-4">
        <Metric label="Sold" value={dashboard.sold} />
        <Metric label="Available" value={dashboard.available} />
        <Metric label="Cancelled" value={dashboard.cancelled} />
        <Metric label="Occupancy" value={`${dashboard.occupancy_pct.toFixed(1)}%`} />
      </section>
      <section className="grid gap-4 lg:grid-cols-3">
        <div className="card">
          <h2 className="text-lg font-bold">Revenue</h2>
          <dl className="mt-4 space-y-3">
            <Row label="Gross" value={money(dashboard.revenue.gross)} />
            <Row label="Refunded" value={money(dashboard.revenue.refunded)} />
            <Row label="Net" value={money(dashboard.revenue.net)} />
          </dl>
        </div>
        <div className="card lg:col-span-2">
          <h2 className="text-lg font-bold">Sales by tier</h2>
          <div className="mt-4 overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead>
                <tr className="border-b">
                  <th className="py-2">Tier</th>
                  <th>Total</th>
                  <th>Sold</th>
                  <th>Revenue</th>
                </tr>
              </thead>
              <tbody>
                {dashboard.by_tier.map((tier) => (
                  <tr className="border-b last:border-0" key={tier.tier}>
                    <td className="py-2 font-semibold">{tier.tier}</td>
                    <td>{tier.total}</td>
                    <td>{tier.sold}</td>
                    <td>{money(tier.revenue)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </section>
      <section className="card">
        <h2 className="text-lg font-bold">Daily sales last 30 days</h2>
        <div className="mt-4 flex h-48 items-end gap-1 overflow-x-auto" aria-label="Bar chart of daily ticket sales">
          {dashboard.daily_sales_last_30d.map((day) => (
            <div key={day.date} className="flex min-w-8 flex-col items-center gap-2">
              <div
                className="w-6 rounded-t bg-brand-600"
                style={{ height: `${(day.tickets_sold / maxDaily) * 10 + 1}rem` }}
                title={`${day.date}: ${day.tickets_sold} tickets, ${money(day.revenue)}`}
              />
              <span className="text-[10px] text-slate-500">{new Date(day.date).getDate()}</span>
            </div>
          ))}
        </div>
      </section>
    </div>
  );
}

function Metric({ label, value }: { label: string; value: string | number }) {
  return (
    <div className="card">
      <p className="text-sm text-slate-600">{label}</p>
      <p className="mt-2 text-3xl font-bold">{value}</p>
    </div>
  );
}
function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between gap-3">
      <dt className="text-slate-600">{label}</dt>
      <dd className="font-bold">{value}</dd>
    </div>
  );
}
