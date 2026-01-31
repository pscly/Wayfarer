"use client";

import { useAuth } from "@/app/providers";
import { API_BASE_URL, apiFetch, ApiError } from "@/lib/api";
import { useRouter } from "next/navigation";
import { useEffect, useMemo, useState } from "react";

type TrackQueryItem = {
  client_point_id: string;
  recorded_at: string;
  latitude: number;
  longitude: number;
  accuracy?: number | null;
  is_dirty: boolean;
  gcj02_latitude?: number | null;
  gcj02_longitude?: number | null;
};

type TrackQueryResponse = {
  items: TrackQueryItem[];
};

type TrackEditCreateResponse = {
  edit_id: string;
  applied_count: number;
};

type ExportCreateResponse = {
  job_id: string;
};

type ExportJobStatusResponse = {
  job_id: string;
  state: string;
  error?: string | null;
};

const EXPORT_TERMINAL_STATES = new Set(["SUCCEEDED", "PARTIAL", "FAILED", "CANCELED"]);

function isoNow(): string {
  return new Date().toISOString();
}

function isoHoursAgo(hours: number): string {
  return new Date(Date.now() - hours * 60 * 60 * 1000).toISOString();
}

function MapPanel() {
  const token = (process.env.NEXT_PUBLIC_MAPBOX_ACCESS_TOKEN || "").trim();
  if (!token) {
    return (
      <div
        data-testid="map-disabled"
        className="rounded-2xl border border-white/10 bg-white/[0.03] p-6"
      >
        <div className="text-sm font-semibold text-white">Map</div>
        <div className="mt-2 text-sm text-white/70">
          Mapbox is disabled (missing
          <code className="mx-1 rounded bg-white/10 px-1 py-0.5 text-xs">
            NEXT_PUBLIC_MAPBOX_ACCESS_TOKEN
          </code>
          ). No map initialization and no network.
        </div>
      </div>
    );
  }

  return (
    <div className="rounded-2xl border border-white/10 bg-white/[0.03] p-6">
      <div className="text-sm font-semibold text-white">Map</div>
      <div className="mt-2 text-sm text-white/70">
        Token present. Map rendering is intentionally deferred.
      </div>
    </div>
  );
}

function parseContentDispositionFilename(value: string | null): string | null {
  if (!value) return null;
  // Minimal parsing for: attachment; filename="export.csv"
  const match = /filename\s*=\s*"([^"]+)"/i.exec(value);
  if (match?.[1]) return match[1];
  const bare = /filename\s*=\s*([^;\s]+)/i.exec(value);
  if (bare?.[1]) return bare[1].replace(/^"|"$/g, "");
  return null;
}

async function readBodyText(res: Response): Promise<string | undefined> {
  try {
    const text = await res.text();
    return text || undefined;
  } catch {
    return undefined;
  }
}

function ExportWizard() {
  const router = useRouter();
  const { accessToken, isHydrating, refresh, setAccessToken } = useAuth();

  const [includeWeather, setIncludeWeather] = useState(false);
  const [jobId, setJobId] = useState<string | null>(null);
  const [jobState, setJobState] = useState<string | null>(null);
  const [jobError, setJobError] = useState<string | null>(null);
  const [isCreating, setIsCreating] = useState(false);
  const [isDownloading, setIsDownloading] = useState(false);
  const [isPolling, setIsPolling] = useState(false);

  const isTerminal = jobState ? EXPORT_TERMINAL_STATES.has(jobState) : false;
  const canDownload = Boolean(jobId && (jobState === "SUCCEEDED" || jobState === "PARTIAL"));

  async function ensureToken(): Promise<string | null> {
    if (isHydrating) return null;
    let token = accessToken;
    if (!token) token = await refresh();
    if (!token) {
      router.replace("/login");
      return null;
    }
    return token;
  }

  useEffect(() => {
    if (!jobId) return;
    let cancelled = false;

    async function poll(): Promise<void> {
      setIsPolling(true);
      try {
        const token = await ensureToken();
        if (!token || cancelled) return;

        // Poll until terminal; keep interval modest to avoid test flakiness.
        while (!cancelled) {
          const status = await apiFetch<ExportJobStatusResponse>(
            `/v1/export/${jobId}`,
            { method: "GET" },
            { accessToken: token, refreshAccessToken: refresh },
          );
          if (cancelled) return;

          setJobState(status?.state || null);
          setJobError(status?.error || null);
          if (status?.state && EXPORT_TERMINAL_STATES.has(status.state)) return;

          await new Promise((r) => setTimeout(r, 750));
        }
      } catch (err) {
        if (cancelled) return;
        if (err instanceof ApiError && err.status === 401) {
          setAccessToken(null);
          router.replace("/login");
          return;
        }
        if (err instanceof Error) setJobError(err.message);
        else setJobError("Failed to poll export job");
      } finally {
        if (!cancelled) setIsPolling(false);
      }
    }

    void poll();
    return () => {
      cancelled = true;
    };
  }, [jobId, accessToken, isHydrating, refresh, router, setAccessToken]);

  return (
    <div
      data-testid="export-wizard"
      className="rounded-2xl border border-white/10 bg-white/[0.03] p-6"
    >
      <div className="flex items-start justify-between gap-3">
        <div>
          <div className="text-sm font-semibold text-white">Export</div>
          <div className="mt-1 text-xs text-white/60">
            Create an export job, watch progress, download artifact.
          </div>
        </div>

        <div
          data-testid="export-job-state"
          className="rounded-full border border-white/10 bg-black/30 px-3 py-1 text-xs font-semibold text-white/80"
        >
          {jobState || (jobId ? "PENDING" : "IDLE")}
        </div>
      </div>

      <div className="mt-4 space-y-3">
        <label className="flex items-center justify-between gap-3 rounded-xl border border-white/10 bg-black/20 px-3 py-2">
          <div>
            <div className="text-sm font-semibold text-white">Include weather</div>
            <div className="mt-0.5 text-xs text-white/60">
              Adds weather context where available.
            </div>
          </div>
          <input
            data-testid="export-include-weather"
            type="checkbox"
            checked={includeWeather}
            onChange={(e) => setIncludeWeather(e.target.checked)}
            className="h-4 w-4 rounded border-white/20 bg-black/30"
          />
        </label>

        {jobId ? (
          <div className="rounded-xl border border-white/10 bg-black/20 px-3 py-2">
            <div className="text-xs font-semibold uppercase tracking-wider text-white/60">
              Job
            </div>
            <div className="mt-1 font-mono text-xs text-white">{jobId}</div>
            {isPolling && !isTerminal ? (
              <div className="mt-2 text-xs text-white/50">Polling…</div>
            ) : null}
          </div>
        ) : null}

        <div className="flex flex-wrap gap-2">
          <button
            type="button"
            data-testid="export-create"
            disabled={isCreating || isPolling}
            onClick={async () => {
              setIsCreating(true);
              setJobError(null);
              setJobState(null);
              setJobId(null);
              try {
                const token = await ensureToken();
                if (!token) return;

                const data = await apiFetch<ExportCreateResponse>(
                  "/v1/export",
                  {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ include_weather: includeWeather }),
                  },
                  { accessToken: token, refreshAccessToken: refresh },
                );
                if (!data?.job_id) throw new Error("Export create response missing job_id");
                setJobId(data.job_id);
                setJobState("RUNNING");
              } catch (err) {
                if (err instanceof ApiError && err.status === 401) {
                  setAccessToken(null);
                  router.replace("/login");
                  return;
                }
                if (err instanceof Error) setJobError(err.message);
                else setJobError("Failed to create export job");
              } finally {
                setIsCreating(false);
              }
            }}
            className="rounded-full border border-emerald-400/30 bg-emerald-500/10 px-4 py-2 text-sm font-semibold text-emerald-100 hover:bg-emerald-500/15 disabled:opacity-50"
          >
            {isCreating ? "Creating…" : "Create export"}
          </button>

          <button
            type="button"
            data-testid="export-download"
            disabled={!canDownload || isDownloading}
            onClick={async () => {
              if (!jobId) return;
              setIsDownloading(true);
              setJobError(null);
              try {
                const token = await ensureToken();
                if (!token) return;

                const url = new URL(`/v1/export/${jobId}/download`, API_BASE_URL).toString();
                const headers = new Headers();
                headers.set("Authorization", `Bearer ${token}`);

                let res = await fetch(url, {
                  method: "GET",
                  headers,
                  credentials: "include",
                });

                if (res.status === 401) {
                  const refreshed = await refresh();
                  if (refreshed) {
                    headers.set("Authorization", `Bearer ${refreshed}`);
                    res = await fetch(url, {
                      method: "GET",
                      headers,
                      credentials: "include",
                    });
                  }
                }

                if (!res.ok) {
                  const bodyText = await readBodyText(res);
                  throw new ApiError(`Download failed`, res.status, bodyText);
                }

                const blob = await res.blob();
                const filename =
                  parseContentDispositionFilename(res.headers.get("content-disposition")) ||
                  `export-${jobId}.bin`;

                const blobUrl = URL.createObjectURL(blob);
                const a = document.createElement("a");
                a.href = blobUrl;
                a.download = filename;
                a.rel = "noopener";
                a.style.display = "none";
                document.body.appendChild(a);
                a.click();
                a.remove();
                setTimeout(() => URL.revokeObjectURL(blobUrl), 1_000);
              } catch (err) {
                if (err instanceof ApiError && err.status === 401) {
                  setAccessToken(null);
                  router.replace("/login");
                  return;
                }
                if (err instanceof Error) setJobError(err.message);
                else setJobError("Failed to download export");
              } finally {
                setIsDownloading(false);
              }
            }}
            className="rounded-full border border-white/15 bg-white/5 px-4 py-2 text-sm font-semibold text-white hover:bg-white/10 disabled:opacity-50"
          >
            {isDownloading ? "Downloading…" : "Download"}
          </button>
        </div>

        {jobError ? (
          <div
            data-testid="export-error"
            className="rounded-xl border border-rose-400/20 bg-rose-500/10 px-3 py-2 text-sm text-rose-200"
          >
            {jobError}
          </div>
        ) : null}
      </div>
    </div>
  );
}

export default function TracksPage() {
  const router = useRouter();
  const { accessToken, isHydrating, refresh, logout, setAccessToken } = useAuth();

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [points, setPoints] = useState<TrackQueryItem[]>([]);
  const [rangeStart, setRangeStart] = useState<string>(() => isoHoursAgo(24));
  const [rangeEnd, setRangeEnd] = useState<string>(() => isoNow());
  const [isDeleting, setIsDeleting] = useState(false);

  const pointsCount = useMemo(() => points.length, [points]);

  useEffect(() => {
    let cancelled = false;

    async function fetchPoints(token: string): Promise<void> {
      const params = new URLSearchParams({
        start: rangeStart,
        end: rangeEnd,
        limit: "5000",
        offset: "0",
      });
      const data = await apiFetch<TrackQueryResponse>(
        `/v1/tracks/query?${params.toString()}`,
        { method: "GET" },
        { accessToken: token, refreshAccessToken: refresh },
      );
      if (cancelled) return;
      setPoints(Array.isArray(data?.items) ? data.items : []);
    }

    async function load() {
      if (isHydrating) return;
      setLoading(true);
      setError(null);

      try {
        let token = accessToken;
        if (!token) {
          token = await refresh();
        }
        if (!token) {
          router.replace("/login");
          return;
        }

        await fetchPoints(token);
      } catch (err) {
        if (cancelled) return;
        if (err instanceof ApiError && err.status === 401) {
          // Access token expired and refresh did not succeed.
          setAccessToken(null);
          router.replace("/login");
          return;
        }
        if (err instanceof Error) setError(err.message);
        else setError("Failed to load track points");
      } finally {
        if (!cancelled) setLoading(false);
      }
    }

    void load();
    return () => {
      cancelled = true;
    };
  }, [accessToken, isHydrating, rangeEnd, rangeStart, refresh, router, setAccessToken]);

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold text-white">Tracks</h1>
          <p className="mt-1 text-sm text-white/70">
            Loaded via the web API client with refresh-once behavior.
          </p>
        </div>

        <button
          type="button"
          onClick={async () => {
            await logout();
            router.replace("/login");
          }}
          className="rounded-full border border-white/15 bg-white/5 px-4 py-2 text-sm font-semibold text-white hover:bg-white/10"
        >
          Sign out
        </button>
      </div>

      <div className="grid gap-4 lg:grid-cols-3">
        <div className="lg:col-span-2 rounded-2xl border border-white/10 bg-white/[0.03] p-6">
          <div className="flex items-center justify-between">
            <div className="text-sm font-semibold text-white">Data</div>
            <div className="text-xs text-white/60">
              count:{" "}
              <span data-testid="tracks-count" className="font-mono text-white">
                {pointsCount}
              </span>
            </div>
          </div>

          <div
            data-testid="timeline"
            className="mt-4 rounded-xl border border-white/10 bg-black/20 p-4"
          >
            <div className="flex flex-wrap items-end gap-3">
              <label className="flex flex-col gap-1">
                <span className="text-xs font-semibold uppercase tracking-wider text-white/60">
                  Range start (ISO8601)
                </span>
                <input
                  data-testid="timeline-start"
                  value={rangeStart}
                  onChange={(e) => setRangeStart(e.target.value)}
                  className="w-[24rem] max-w-full rounded-lg border border-white/10 bg-black/30 px-3 py-2 font-mono text-xs text-white placeholder:text-white/30"
                  placeholder="2026-01-31T00:00:00.000Z"
                  inputMode="text"
                />
              </label>

              <label className="flex flex-col gap-1">
                <span className="text-xs font-semibold uppercase tracking-wider text-white/60">
                  Range end (ISO8601)
                </span>
                <input
                  data-testid="timeline-end"
                  value={rangeEnd}
                  onChange={(e) => setRangeEnd(e.target.value)}
                  className="w-[24rem] max-w-full rounded-lg border border-white/10 bg-black/30 px-3 py-2 font-mono text-xs text-white placeholder:text-white/30"
                  placeholder="2026-01-31T23:59:59.999Z"
                  inputMode="text"
                />
              </label>

              <button
                type="button"
                data-testid="timeline-delete"
                disabled={loading || isDeleting}
                onClick={async () => {
                  setIsDeleting(true);
                  setError(null);
                  try {
                    let token = accessToken;
                    if (!token) token = await refresh();
                    if (!token) {
                      router.replace("/login");
                      return;
                    }

                    await apiFetch<TrackEditCreateResponse>(
                      "/v1/tracks/edits",
                      {
                        method: "POST",
                        headers: { "Content-Type": "application/json" },
                        body: JSON.stringify({
                          type: "DELETE_RANGE",
                          start: rangeStart,
                          end: rangeEnd,
                        }),
                      },
                      { accessToken: token, refreshAccessToken: refresh },
                    );

                    // Refresh points in the current query range after applying the edit.
                    setLoading(true);
                    const params = new URLSearchParams({
                      start: rangeStart,
                      end: rangeEnd,
                      limit: "5000",
                      offset: "0",
                    });
                    const data = await apiFetch<TrackQueryResponse>(
                      `/v1/tracks/query?${params.toString()}`,
                      { method: "GET" },
                      { accessToken: token, refreshAccessToken: refresh },
                    );
                    setPoints(Array.isArray(data?.items) ? data.items : []);
                  } catch (err) {
                    if (err instanceof ApiError && err.status === 401) {
                      setAccessToken(null);
                      router.replace("/login");
                      return;
                    }
                    if (err instanceof Error) setError(err.message);
                    else setError("Failed to delete range");
                  } finally {
                    setIsDeleting(false);
                    setLoading(false);
                  }
                }}
                className="rounded-full border border-rose-400/30 bg-rose-500/10 px-4 py-2 text-sm font-semibold text-rose-100 hover:bg-rose-500/15 disabled:opacity-50"
              >
                {isDeleting ? "Deleting…" : "Delete"}
              </button>
            </div>
            <div className="mt-2 text-xs text-white/50">
              Tip: send UTC timestamps (with <span className="font-mono">Z</span>) to match the backend contract.
            </div>
          </div>

          {loading ? (
            <div className="mt-6 text-sm text-white/70">Loading...</div>
          ) : error ? (
            <div className="mt-6 rounded-xl border border-rose-400/20 bg-rose-500/10 px-3 py-2 text-sm text-rose-200">
              {error}
            </div>
          ) : points.length === 0 ? (
            <div className="mt-6 text-sm text-white/70">No points in range.</div>
          ) : (
            <div className="mt-6 overflow-hidden rounded-xl border border-white/10">
              <table className="w-full text-left text-sm">
                <thead className="bg-black/20 text-xs uppercase tracking-wider text-white/60">
                  <tr>
                    <th className="px-3 py-2">Recorded at</th>
                    <th className="px-3 py-2">Lat</th>
                    <th className="px-3 py-2">Lon</th>
                    <th className="px-3 py-2">Acc</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-white/10">
                  {points.map((p) => (
                    <tr
                      key={p.client_point_id}
                      data-testid="track-point-row"
                      className="hover:bg-white/5"
                    >
                      <td className="px-3 py-2 font-mono text-xs text-white">
                        {p.recorded_at}
                      </td>
                      <td className="px-3 py-2 font-mono text-xs text-white/70">
                        {p.latitude.toFixed(6)}
                      </td>
                      <td className="px-3 py-2 font-mono text-xs text-white/70">
                        {p.longitude.toFixed(6)}
                      </td>
                      <td className="px-3 py-2 font-mono text-xs text-white/60">
                        {p.accuracy ?? "-"}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>

        <div className="space-y-4">
          <ExportWizard />
          <MapPanel />
        </div>
      </div>
    </div>
  );
}
