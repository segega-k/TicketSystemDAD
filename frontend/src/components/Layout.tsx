import { Link, NavLink, Outlet, useNavigate } from 'react-router-dom';
import { authApi, problemMessage } from '../lib/api';
import { useAuthStore } from '../lib/authStore';

const navClass = ({ isActive }: { isActive: boolean }) =>
  `rounded-lg px-3 py-2 text-sm font-medium ${isActive ? 'bg-white text-brand-700 shadow-sm' : 'text-slate-200 hover:bg-slate-700'}`;

export function Layout() {
  const { user, clear, isAuthenticated } = useAuthStore();
  const navigate = useNavigate();
  const authed = isAuthenticated();
  async function logout() {
    try {
      await authApi.logout();
    } catch (error) {
      console.warn(problemMessage(error));
    }
    clear();
    navigate('/');
  }
  return (
    <div className="min-h-screen">
      <header className="bg-slate-900 text-white">
        <div className="container-page flex flex-col gap-3 py-4 md:flex-row md:items-center md:justify-between">
          <Link to="/" className="text-xl font-bold text-white hover:text-white">
            TZ Ticketing
          </Link>
          <nav aria-label="Primary" className="flex flex-wrap items-center gap-2">
            <NavLink to="/events" className={navClass}>
              Events
            </NavLink>
            {authed && (
              <NavLink to="/me/bookings" className={navClass}>
                My bookings
              </NavLink>
            )}
            {(user?.role === 'ORGANIZER' || user?.role === 'ADMIN') && (
              <NavLink to="/organizer/events/new" className={navClass}>
                Create event
              </NavLink>
            )}
            {user?.role === 'ADMIN' && (
              <NavLink to="/admin/users" className={navClass}>
                Admin · Users
              </NavLink>
            )}
            {!authed ? (
              <>
                <NavLink to="/login" className={navClass}>
                  Login
                </NavLink>
                <NavLink to="/register" className={navClass}>
                  Register
                </NavLink>
              </>
            ) : (
              <button
                className="btn btn-secondary border-slate-500 bg-slate-800 text-white hover:bg-slate-700"
                onClick={logout}
              >
                Logout
              </button>
            )}
          </nav>
        </div>
      </header>
      <main className="container-page py-8">
        <Outlet />
      </main>
    </div>
  );
}
