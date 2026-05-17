import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, useNavigate } from 'react-router-dom';
import { z } from 'zod';
import { ErrorBanner } from '../components/ErrorBanner';
import { authApi, problemMessage } from '../lib/api';

const schema = z.object({
  username: z.string().min(3).max(50),
  full_name: z.string().min(2).max(120),
  email: z.string().email(),
  password: z.string().min(8, 'Use at least 8 characters'),
});
type FormValues = z.infer<typeof schema>;

export function RegisterPage() {
  const [error, setError] = useState('');
  const [created, setCreated] = useState(false);
  const navigate = useNavigate();
  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<FormValues>({ resolver: zodResolver(schema) });
  async function onSubmit(values: FormValues) {
    setError('');
    try {
      await authApi.register(values);
      setCreated(true);
      setTimeout(() => navigate('/login'), 1000);
    } catch (err) {
      setError(problemMessage(err));
    }
  }
  return (
    <section className="mx-auto max-w-lg card">
      <h1 className="text-2xl font-bold">Create account</h1>
      {created && (
        <div className="mt-3 rounded-xl border border-emerald-200 bg-emerald-50 p-3 text-emerald-800">
          Account created. Redirecting to login…
        </div>
      )}
      <ErrorBanner message={error} />
      <form onSubmit={handleSubmit(onSubmit)} className="mt-5 grid gap-4">
        <label>
          <span className="label">Username</span>
          <input className="input mt-1" {...register('username')} />
          {errors.username && <span className="text-sm text-rose-700">{errors.username.message}</span>}
        </label>
        <label>
          <span className="label">Full name</span>
          <input className="input mt-1" autoComplete="name" {...register('full_name')} />
          {errors.full_name && <span className="text-sm text-rose-700">{errors.full_name.message}</span>}
        </label>
        <label>
          <span className="label">Email</span>
          <input className="input mt-1" autoComplete="email" {...register('email')} />
          {errors.email && <span className="text-sm text-rose-700">{errors.email.message}</span>}
        </label>
        <label>
          <span className="label">Password</span>
          <input className="input mt-1" type="password" autoComplete="new-password" {...register('password')} />
          {errors.password && <span className="text-sm text-rose-700">{errors.password.message}</span>}
        </label>
        <button className="btn btn-primary" disabled={isSubmitting}>
          {isSubmitting ? 'Creating…' : 'Register'}
        </button>
      </form>
      <p className="mt-4 text-sm text-slate-600">
        Already registered? <Link to="/login">Sign in</Link>.
      </p>
    </section>
  );
}
