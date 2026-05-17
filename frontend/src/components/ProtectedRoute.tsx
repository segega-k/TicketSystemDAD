import { useEffect } from 'react';
import { Navigate, Outlet, useLocation } from 'react-router-dom';
import type { Role } from '../types';
import { useAuthStore } from '../lib/authStore';

export function ProtectedRoute({ roles }: { roles?: Role[] }) {
  const location = useLocation();
  const { isAuthenticated, hasRole, accessTokenExpired, clear } = useAuthStore();
  const expired = accessTokenExpired();

  useEffect(() => {
    if (expired) clear();
  }, [clear, expired]);

  if (expired || !isAuthenticated())
    return <Navigate to={`/login?next=${encodeURIComponent(location.pathname)}`} replace />;
  if (!hasRole(roles))
    return (
      <div className="card" role="alert">
        <h1 className="text-xl font-semibold">Access denied</h1>
        <p className="mt-2 text-slate-600">Your account is not allowed to open this page.</p>
      </div>
    );
  return <Outlet />;
}
