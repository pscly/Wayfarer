import Link from "next/link";

export default function Home() {
  return (
    <div className="space-y-10">
      <section className="rounded-3xl border border-white/10 bg-white/[0.04] p-8 shadow-[0_0_0_1px_rgba(255,255,255,0.03)]">
        <p className="text-xs tracking-[0.25em] text-white/60">WAYFARER</p>
        <h1 className="mt-3 text-balance text-3xl font-semibold text-white sm:text-4xl">
          Web Console
        </h1>
        <p className="mt-3 max-w-2xl text-white/70">
          Dark-first admin surface for authentication and tracks. No external
          assets, no third-party network.
        </p>

        <div className="mt-6 flex flex-wrap gap-3">
          <Link
            href="/login"
            className="inline-flex items-center justify-center rounded-full bg-[color:var(--accent)] px-5 py-2.5 text-sm font-semibold text-black hover:brightness-110"
          >
            Sign in
          </Link>
          <Link
            href="/tracks"
            className="inline-flex items-center justify-center rounded-full border border-white/15 bg-white/5 px-5 py-2.5 text-sm font-semibold text-white hover:bg-white/10"
          >
            Go to tracks
          </Link>
        </div>
      </section>

      <section className="grid gap-4 sm:grid-cols-2">
        <div className="rounded-2xl border border-white/10 bg-white/[0.03] p-6">
          <h2 className="text-sm font-semibold text-white">Auth</h2>
          <p className="mt-2 text-sm text-white/70">
            Uses cookie + CSRF refresh. Web requests always send
            <code className="mx-1 rounded bg-white/10 px-1 py-0.5 text-xs">
              credentials: include
            </code>
            .
          </p>
        </div>
        <div className="rounded-2xl border border-white/10 bg-white/[0.03] p-6">
          <h2 className="text-sm font-semibold text-white">Tracks</h2>
          <p className="mt-2 text-sm text-white/70">
            Minimal data layer with a count and a simple list/table.
          </p>
        </div>
      </section>
    </div>
  );
}
