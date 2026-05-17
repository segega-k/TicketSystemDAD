import { useEffect, useMemo, useState } from 'react';
import {
  adminApi,
  problemMessage,
  type AdminUser,
  type AdminUserCreate,
  type AdminUserUpdate,
} from '../lib/api';
import { ErrorBanner } from '../components/ErrorBanner';
import { Loading } from '../components/Loading';

type FormState = {
  email: string;
  password: string;
  role: 'ORGANIZER' | 'ANALYST';
  display_name: string;
};

const emptyForm: FormState = { email: '', password: '', role: 'ORGANIZER', display_name: '' };

export function AdminUsersPage() {
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [form, setForm] = useState<FormState>(emptyForm);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editPassword, setEditPassword] = useState('');
  const [roleFilter, setRoleFilter] = useState<'' | 'ORGANIZER' | 'ANALYST'>('');

  async function reload() {
    setLoading(true);
    setError(null);
    try {
      const items = await adminApi.listUsers(roleFilter || undefined);
      setUsers(items);
    } catch (e) {
      setError(problemMessage(e));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void reload();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [roleFilter]);

  const filtered = useMemo(() => users, [users]);

  async function handleCreate(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const payload: AdminUserCreate = {
        email: form.email.trim(),
        password: form.password,
        role: form.role,
        display_name: form.display_name.trim() || undefined,
      };
      await adminApi.createUser(payload);
      setForm(emptyForm);
      await reload();
    } catch (err) {
      setError(problemMessage(err));
    } finally {
      setBusy(false);
    }
  }

  async function handleDelete(u: AdminUser) {
    if (!confirm(`Delete user ${u.email}?`)) return;
    setBusy(true);
    setError(null);
    try {
      await adminApi.deleteUser(u.id);
      await reload();
    } catch (err) {
      setError(problemMessage(err));
    } finally {
      setBusy(false);
    }
  }

  async function handleSaveEdit(u: AdminUser) {
    setBusy(true);
    setError(null);
    try {
      const payload: AdminUserUpdate = {
        email: u.email,
        role: u.role === 'ORGANIZER' || u.role === 'ANALYST' ? u.role : undefined,
        display_name: u.display_name ?? u.displayName ?? undefined,
      };
      if (editPassword.trim().length >= 8) payload.password = editPassword;
      await adminApi.updateUser(u.id, payload);
      setEditingId(null);
      setEditPassword('');
      await reload();
    } catch (err) {
      setError(problemMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="space-y-6">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold">Admin · Accounts</h1>
          <p className="text-sm text-slate-600">Manage organizer and analyst accounts.</p>
        </div>
        <label className="flex items-center gap-2 text-sm">
          <span>Role</span>
          <select
            className="rounded border px-2 py-1"
            value={roleFilter}
            onChange={(e) => setRoleFilter(e.target.value as '' | 'ORGANIZER' | 'ANALYST')}
          >
            <option value="">All managed</option>
            <option value="ORGANIZER">Organizer</option>
            <option value="ANALYST">Analyst</option>
          </select>
        </label>
      </header>

      {error && <ErrorBanner message={error} />}

      <form onSubmit={handleCreate} className="card space-y-3">
        <h2 className="text-lg font-semibold">Create account</h2>
        <div className="grid gap-3 md:grid-cols-2">
          <label className="flex flex-col text-sm">
            <span className="mb-1 font-medium">Email</span>
            <input
              className="rounded border px-3 py-2"
              type="email"
              required
              value={form.email}
              onChange={(e) => setForm((f) => ({ ...f, email: e.target.value }))}
            />
          </label>
          <label className="flex flex-col text-sm">
            <span className="mb-1 font-medium">Display name</span>
            <input
              className="rounded border px-3 py-2"
              value={form.display_name}
              onChange={(e) => setForm((f) => ({ ...f, display_name: e.target.value }))}
            />
          </label>
          <label className="flex flex-col text-sm">
            <span className="mb-1 font-medium">Password (≥ 8 chars)</span>
            <input
              className="rounded border px-3 py-2"
              type="password"
              minLength={8}
              required
              value={form.password}
              onChange={(e) => setForm((f) => ({ ...f, password: e.target.value }))}
            />
          </label>
          <label className="flex flex-col text-sm">
            <span className="mb-1 font-medium">Role</span>
            <select
              className="rounded border px-3 py-2"
              value={form.role}
              onChange={(e) => setForm((f) => ({ ...f, role: e.target.value as 'ORGANIZER' | 'ANALYST' }))}
            >
              <option value="ORGANIZER">Organizer</option>
              <option value="ANALYST">Analyst</option>
            </select>
          </label>
        </div>
        <button className="btn btn-primary" type="submit" disabled={busy}>
          {busy ? 'Saving…' : 'Create account'}
        </button>
      </form>

      <div className="card">
        <h2 className="mb-3 text-lg font-semibold">Accounts</h2>
        {loading ? (
          <Loading />
        ) : filtered.length === 0 ? (
          <p className="text-sm text-slate-600">No accounts yet.</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="min-w-full text-sm">
              <thead>
                <tr className="border-b bg-slate-50 text-left">
                  <th className="px-3 py-2">Email</th>
                  <th className="px-3 py-2">Role</th>
                  <th className="px-3 py-2">Display name</th>
                  <th className="px-3 py-2">Created</th>
                  <th className="px-3 py-2 text-right">Actions</th>
                </tr>
              </thead>
              <tbody>
                {filtered.map((u) => {
                  const editing = editingId === u.id;
                  return (
                    <tr key={u.id} className="border-b last:border-0">
                      <td className="px-3 py-2 font-mono text-xs">{u.email}</td>
                      <td className="px-3 py-2">
                        {editing ? (
                          <select
                            className="rounded border px-2 py-1"
                            value={u.role}
                            onChange={(e) =>
                              setUsers((all) =>
                                all.map((x) =>
                                  x.id === u.id
                                    ? { ...x, role: e.target.value as AdminUser['role'] }
                                    : x
                                )
                              )
                            }
                          >
                            <option value="ORGANIZER">Organizer</option>
                            <option value="ANALYST">Analyst</option>
                          </select>
                        ) : (
                          <span className="inline-block rounded bg-slate-100 px-2 py-0.5 text-xs font-medium">
                            {u.role}
                          </span>
                        )}
                      </td>
                      <td className="px-3 py-2">
                        {editing ? (
                          <input
                            className="w-full rounded border px-2 py-1"
                            value={u.display_name ?? ''}
                            onChange={(e) =>
                              setUsers((all) =>
                                all.map((x) => (x.id === u.id ? { ...x, display_name: e.target.value } : x))
                              )
                            }
                          />
                        ) : (
                          u.display_name ?? u.displayName ?? '—'
                        )}
                      </td>
                      <td className="px-3 py-2 text-xs text-slate-500">
                        {u.created_at ? new Date(u.created_at).toLocaleString() : '—'}
                      </td>
                      <td className="px-3 py-2 text-right">
                        {editing ? (
                          <div className="flex flex-col items-end gap-1">
                            <input
                              className="rounded border px-2 py-1 text-xs"
                              type="password"
                              placeholder="New password (optional, ≥ 8)"
                              value={editPassword}
                              onChange={(e) => setEditPassword(e.target.value)}
                            />
                            <div className="flex gap-2">
                              <button
                                className="btn btn-primary px-2 py-1 text-xs"
                                onClick={() => handleSaveEdit(u)}
                                disabled={busy}
                              >
                                Save
                              </button>
                              <button
                                className="btn btn-secondary px-2 py-1 text-xs"
                                onClick={() => {
                                  setEditingId(null);
                                  setEditPassword('');
                                  void reload();
                                }}
                              >
                                Cancel
                              </button>
                            </div>
                          </div>
                        ) : (
                          <div className="flex justify-end gap-2">
                            <button
                              className="btn btn-secondary px-2 py-1 text-xs"
                              onClick={() => {
                                setEditingId(u.id);
                                setEditPassword('');
                              }}
                            >
                              Edit
                            </button>
                            <button
                              className="btn px-2 py-1 text-xs text-rose-700 ring-1 ring-rose-300 hover:bg-rose-50"
                              onClick={() => handleDelete(u)}
                              disabled={busy}
                            >
                              Delete
                            </button>
                          </div>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </section>
  );
}
