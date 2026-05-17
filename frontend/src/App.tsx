import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { Layout } from './components/Layout';
import { ProtectedRoute } from './components/ProtectedRoute';
import { CheckoutPage } from './pages/CheckoutPage';
import { EventDetailPage } from './pages/EventDetailPage';
import { EventsListPage } from './pages/EventsListPage';
import { LandingPage } from './pages/LandingPage';
import { LoginPage } from './pages/LoginPage';
import { MyBookingsPage } from './pages/MyBookingsPage';
import { OrganizerCreateEventPage } from './pages/OrganizerCreateEventPage';
import { OrganizerDashboardPage } from './pages/OrganizerDashboardPage';
import { RegisterPage } from './pages/RegisterPage';
import { AdminUsersPage } from './pages/AdminUsersPage';

export function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<Layout />}>
          <Route index element={<LandingPage />} />
          <Route path="login" element={<LoginPage />} />
          <Route path="register" element={<RegisterPage />} />
          <Route path="events" element={<EventsListPage />} />
          <Route path="events/:id" element={<EventDetailPage />} />
          <Route element={<ProtectedRoute roles={['CUSTOMER', 'ADMIN']} />}>
            <Route path="checkout/:holdGroupId" element={<CheckoutPage />} />
            <Route path="me/bookings" element={<MyBookingsPage />} />
          </Route>
          <Route element={<ProtectedRoute roles={['ORGANIZER', 'ADMIN']} />}>
            <Route path="organizer/events/new" element={<OrganizerCreateEventPage />} />
          </Route>
          <Route element={<ProtectedRoute roles={['ORGANIZER', 'ANALYST', 'ADMIN']} />}>
            <Route path="organizer/events/:id/dashboard" element={<OrganizerDashboardPage />} />
          </Route>
          <Route element={<ProtectedRoute roles={['ADMIN']} />}>
            <Route path="admin/users" element={<AdminUsersPage />} />
          </Route>
          <Route path="*" element={<Navigate to="/" replace />} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
}
