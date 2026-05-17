export function formatDateTime(value?: string): string {
  if (!value) return '—';
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value));
}

export function money(value?: string | number): string {
  if (value === undefined || value === null || value === '') return '—';
  const amount = typeof value === 'number' ? value : Number(value);
  if (Number.isNaN(amount)) return String(value);
  return new Intl.NumberFormat(undefined, { style: 'currency', currency: 'USD' }).format(amount);
}

export function makeIdempotencyKey(): string {
  if ('crypto' in window && 'randomUUID' in crypto) return crypto.randomUUID();
  return `client-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}
