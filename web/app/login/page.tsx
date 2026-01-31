"use client";

import { useAuth } from "@/app/providers";
import { ApiError } from "@/lib/api";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";

export default function LoginPage() {
  const router = useRouter();
  const { accessToken, isHydrating, login } = useAuth();

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (isHydrating) return;
    if (accessToken) router.replace("/tracks");
  }, [accessToken, isHydrating, router]);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      await login(email, password);
      router.replace("/tracks");
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.bodyText || `Login failed (${err.status})`);
      } else if (err instanceof Error) {
        setError(err.message);
      } else {
        setError("Login failed");
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="mx-auto w-full max-w-md">
      <div className="rounded-3xl border border-white/10 bg-white/[0.04] p-8">
        <h1 className="text-2xl font-semibold text-white">Sign in</h1>
        <p className="mt-2 text-sm text-white/70">
          Use your backend credentials. Refresh uses the
          <code className="mx-1 rounded bg-white/10 px-1 py-0.5 text-xs">
            wf_csrf
          </code>
          cookie.
        </p>

        <form onSubmit={onSubmit} className="mt-6 space-y-4">
          <label className="block">
            <span className="text-xs font-medium tracking-wide text-white/70">
              Email
            </span>
            <input
              data-testid="login-email"
              type="email"
              autoComplete="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              className="mt-2 w-full rounded-xl border border-white/10 bg-black/20 px-3 py-2 text-sm text-white placeholder:text-white/35 focus:border-white/20 focus:outline-none"
              placeholder="you@example.com"
              required
            />
          </label>

          <label className="block">
            <span className="text-xs font-medium tracking-wide text-white/70">
              Password
            </span>
            <input
              data-testid="login-password"
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="mt-2 w-full rounded-xl border border-white/10 bg-black/20 px-3 py-2 text-sm text-white placeholder:text-white/35 focus:border-white/20 focus:outline-none"
              required
            />
          </label>

          {error ? (
            <div
              data-testid="login-error"
              className="rounded-xl border border-rose-400/20 bg-rose-500/10 px-3 py-2 text-sm text-rose-200"
            >
              {error}
            </div>
          ) : null}

          <button
            data-testid="login-submit"
            type="submit"
            disabled={submitting}
            className="w-full rounded-xl bg-[color:var(--accent)] px-4 py-2.5 text-sm font-semibold text-black disabled:opacity-60"
          >
            {submitting ? "Signing in..." : "Sign in"}
          </button>
        </form>
      </div>
    </div>
  );
}
