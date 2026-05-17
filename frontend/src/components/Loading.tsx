export function Loading({ label = 'Loading' }: { label?: string }) {
  return (
    <div className="card animate-pulse text-sm text-slate-600" role="status" aria-live="polite">
      {label}…
    </div>
  );
}
