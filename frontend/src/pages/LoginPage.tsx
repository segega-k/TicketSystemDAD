import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { z } from 'zod';
import { ErrorBanner } from '../components/ErrorBanner';
import { authApi, problemMessage } from '../lib/api';
import { useAuthStore } from '../lib/authStore';

const schema = z.object({ email: z.string().email(), password: z.string().min(1) });
type FormValues = z.infer<typeof schema>;

export function LoginPage() {
  const [error, setError] = useState('');
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const setTokens = useAuthStore((s) => s.setTokens);
  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<FormValues>({ resolver: zodResolver(schema) });
  async function onSubmit(values: FormValues) {
    setError('');
    try {
      setTokens(await authApi.login(values.email, values.password));
      navigate(params.get('next') || '/events', { replace: true });
    } catch (err) {
      setError(problemMessage(err));
    }
  }
  return (
    <section className="mx-auto max-w-md card">
      <h1 className="text-2xl font-bold">Login</h1>
      <ErrorBanner message={error} />
      <form onSubmit={handleSubmit(onSubmit)} className="mt-5 space-y-4">
        <label className="block">
          <span className="label">Email</span>
          <input className="input mt-1" autoComplete="email" {...register('email')} />
          {errors.email && <span className="text-sm text-rose-700">{errors.email.message}</span>}
        </label>
        <label className="block">
          <span className="label">Password</span>
          <input className="input mt-1" type="password" autoComplete="current-password" {...register('password')} />
          {errors.password && <span className="text-sm text-rose-700">{errors.password.message}</span>}
        </label>
        <button className="btn btn-primary w-full" disabled={isSubmitting}>
          {isSubmitting ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
      <p className="mt-4 text-sm text-slate-600">
        New here? <Link to="/register">Create an account</Link>.
      </p>
    </section>
  );
}
