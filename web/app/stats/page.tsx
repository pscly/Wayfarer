"use client";

import { useAuth } from "@/app/providers";
import { apiFetch, ApiError } from "@/lib/api";
import { Button } from "@/components/ui/Button";
import { Card, CardBody, CardHeader } from "@/components/ui/Card";
import { Notice } from "@/components/ui/Notice";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useMemo, useState } from "react";

type StepsDailyItem = {
  day: string; // YYYY-MM-DD (UTC)
  steps: number;
};

type StepsDailyResponse = {
  items: StepsDailyItem[];
};

type StepsHourlyItem = {
  hour_start: string; // ISO8601 (UTC)
  steps: number;
};

type StepsHourlyResponse = {
  items: StepsHourlyItem[];
};

type LifeEventItem = {
  id: string;
  event_type: string;
  start_at: string;
  end_at: string;
  location_name?: string | null;
  manual_note?: string | null;
  latitude?: number | null;
  longitude?: number | null;
};

type LifeEventListResponse = {
  items: LifeEventItem[];
};

function isoNow(): string {
  return new Date().toISOString();
}

function isoUtcDayStart(dayIso: string): string {
  return `${dayIso}T00:00:00Z`;
}

function isoUtcDayEnd(dayIso: string): string {
  return `${dayIso}T23:59:59Z`;
}

function clampNonNegative(n: number): number {
  if (!Number.isFinite(n)) return 0;
  return n < 0 ? 0 : n;
}

function formatHourLabel(hourStartIso: string): string {
  const d = new Date(hourStartIso);
  if (Number.isNaN(d.getTime())) return hourStartIso;
  const hh = String(d.getUTCHours()).padStart(2, "0");
  return `${hh}:00`;
}

function sumSteps(items: Array<{ steps: number }>): number {
  return items.reduce((acc, it) => acc + clampNonNegative(it.steps), 0);
}

function computeUtcWindowStartIso(windowDays: number): string {
  const now = new Date();
  const start = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate()));
  start.setUTCDate(start.getUTCDate() - Math.max(0, windowDays - 1));
  return start.toISOString();
}

function HourlyBars({ items }: { items: StepsHourlyItem[] }) {
  const max = Math.max(1, ...items.map((it) => clampNonNegative(it.steps)));

  return (
    <div data-testid="stats-hourly" className="space-y-3">
      <div className="grid gap-2">
        {items.map((it) => {
          const steps = clampNonNegative(it.steps);
          const pct = Math.round((steps / max) * 100);
          return (
            <div
              key={it.hour_start}
              className="grid grid-cols-[5rem,1fr,4rem] items-center gap-3"
            >
              <div className="text-xs text-foreground/70">{formatHourLabel(it.hour_start)}</div>
              <div className="h-2 overflow-hidden rounded-full border border-border bg-black/20">
                <div
                  className="h-full rounded-full bg-emerald-400/80"
                  style={{ width: `${pct}%` }}
                />
              </div>
              <div className="text-right font-mono text-xs text-foreground">{steps}</div>
            </div>
          );
        })}
      </div>

      <div className="text-xs text-foreground/60">
        说明：小时桶按 UTC 展示（后续可加时区偏移）。
      </div>
    </div>
  );
}

export default function StatsPage() {
  const router = useRouter();
  const { accessToken, isHydrating, refresh, setAccessToken } = useAuth();

  const [windowDays, setWindowDays] = useState(7);
  const [reloadToken, setReloadToken] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [daily, setDaily] = useState<StepsDailyItem[]>([]);
  const [selectedDay, setSelectedDay] = useState<string | null>(null);

  const [hourly, setHourly] = useState<StepsHourlyItem[]>([]);
  const [marks, setMarks] = useState<LifeEventItem[]>([]);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<string | null>(null);

  const totalSteps = useMemo(() => sumSteps(daily), [daily]);

  const ensureToken = useCallback(async (): Promise<string | null> => {
    if (isHydrating) return null;
    let token = accessToken;
    if (!token) token = await refresh();
    if (!token) {
      router.replace("/login");
      return null;
    }
    return token;
  }, [accessToken, isHydrating, refresh, router]);

  useEffect(() => {
    let cancelled = false;

    async function load(): Promise<void> {
      if (isHydrating) return;
      setLoading(true);
      setError(null);

      try {
        const token = await ensureToken();
        if (!token || cancelled) return;

        const startIso = computeUtcWindowStartIso(windowDays);
        const endIso = isoNow();

        const params = new URLSearchParams({ start: startIso, end: endIso });
        const data = await apiFetch<StepsDailyResponse>(
          `/v1/stats/steps/daily?${params.toString()}`,
          { method: "GET" },
          { accessToken: token, refreshAccessToken: refresh },
        );

        if (cancelled) return;
        const items = Array.isArray(data?.items) ? data.items : [];
        setDaily(items);
      } catch (err) {
        if (cancelled) return;
        if (err instanceof ApiError && err.status === 401) {
          setAccessToken(null);
          router.replace("/login");
          return;
        }
        if (err instanceof Error) setError(err.message);
        else setError("加载步数统计失败");
      } finally {
        if (!cancelled) setLoading(false);
      }
    }

    void load();
    return () => {
      cancelled = true;
    };
  }, [ensureToken, isHydrating, refresh, router, setAccessToken, windowDays, reloadToken]);

  useEffect(() => {
    let cancelled = false;
    const day = selectedDay;
    if (!day) {
      setHourly([]);
      setMarks([]);
      setDetailError(null);
      setDetailLoading(false);
      return;
    }

    const dayIso: string = day;

    async function loadDetail(): Promise<void> {
      setDetailLoading(true);
      setDetailError(null);
      try {
        const token = await ensureToken();
        if (!token || cancelled) return;

        const startIso = isoUtcDayStart(dayIso);
        const endIso = isoUtcDayEnd(dayIso);

        const hourlyParams = new URLSearchParams({ start: startIso, end: endIso });
        const hourlyData = await apiFetch<StepsHourlyResponse>(
          `/v1/stats/steps/hourly?${hourlyParams.toString()}`,
          { method: "GET" },
          { accessToken: token, refreshAccessToken: refresh },
        );

        const marksParams = new URLSearchParams({ start: startIso, end: endIso });
        const marksData = await apiFetch<LifeEventListResponse>(
          `/v1/life-events?${marksParams.toString()}`,
          { method: "GET" },
          { accessToken: token, refreshAccessToken: refresh },
        );

        if (cancelled) return;
        setHourly(Array.isArray(hourlyData?.items) ? hourlyData.items : []);
        setMarks(Array.isArray(marksData?.items) ? marksData.items : []);
      } catch (err) {
        if (cancelled) return;
        if (err instanceof ApiError && err.status === 401) {
          setAccessToken(null);
          router.replace("/login");
          return;
        }
        if (err instanceof Error) setDetailError(err.message);
        else setDetailError("加载日详情失败");
      } finally {
        if (!cancelled) setDetailLoading(false);
      }
    }

    void loadDetail();
    return () => {
      cancelled = true;
    };
  }, [ensureToken, refresh, router, selectedDay, setAccessToken]);

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold text-foreground">统计</h1>
          <p className="mt-1 text-sm text-foreground/70">
            步数按天汇总，点击某天可查看按小时下钻与标记。
          </p>
        </div>
      </div>

      <Card>
        <CardHeader className="flex flex-wrap items-center justify-between gap-3">
          <div className="text-sm font-semibold text-foreground">时间窗口</div>
          <div className="flex flex-wrap gap-2">
            <Button
              type="button"
              size="sm"
              variant={windowDays === 7 ? "primary" : "secondary"}
              onClick={() => setWindowDays(7)}
            >
              近 7 天
            </Button>
            <Button
              type="button"
              size="sm"
              variant={windowDays === 30 ? "primary" : "secondary"}
              onClick={() => setWindowDays(30)}
            >
              近 30 天
            </Button>
            <Button
              type="button"
              size="sm"
              variant="ghost"
              onClick={() => {
                setSelectedDay(null);
              }}
            >
              关闭日详情
            </Button>
          </div>
        </CardHeader>
        <CardBody className="space-y-4">
          {error ? <Notice variant="error">{error}</Notice> : null}

          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="text-sm text-foreground/70">
              总步数：{" "}
              <span className="font-mono text-foreground" data-testid="stats-total-steps">
                {totalSteps}
              </span>
            </div>
            <Button
              type="button"
              size="sm"
              variant="secondary"
              onClick={() => {
                setReloadToken((x) => x + 1);
              }}
              disabled={loading}
            >
              {loading ? "加载中…" : "刷新"}
            </Button>
          </div>

          <div data-testid="stats-daily" className="grid gap-2">
            {daily.map((it) => {
              const isSelected = selectedDay === it.day;
              return (
                <button
                  key={it.day}
                  type="button"
                  data-testid={`stats-day-${it.day}`}
                  onClick={() => setSelectedDay(it.day)}
                  className={[
                    "flex items-center justify-between rounded-2xl border px-4 py-3 text-left",
                    isSelected ? "border-emerald-400/40 bg-emerald-400/10" : "border-border bg-black/20 hover:bg-black/25",
                  ].join(" ")}
                >
                  <div className="text-sm font-semibold text-foreground">{it.day}</div>
                  <div className="font-mono text-sm text-foreground">{clampNonNegative(it.steps)}</div>
                </button>
              );
            })}
            {!loading && daily.length === 0 ? (
              <Notice>该时间窗口内暂无步数数据。</Notice>
            ) : null}
          </div>
        </CardBody>
      </Card>

      {selectedDay ? (
        <Card>
          <CardHeader className="flex flex-wrap items-center justify-between gap-3">
            <div className="text-sm font-semibold text-foreground">
              日详情：<span className="font-mono">{selectedDay}</span>
            </div>
            <div className="text-xs text-foreground/60">
              该日总步数：<span className="font-mono">{sumSteps(hourly)}</span>
            </div>
          </CardHeader>
          <CardBody className="space-y-4">
            {detailError ? <Notice variant="error">{detailError}</Notice> : null}
            {detailLoading ? <Notice>加载中…</Notice> : null}

            {hourly.length > 0 ? <HourlyBars items={hourly} /> : <Notice>暂无小时数据。</Notice>}

            <div data-testid="stats-marks" className="space-y-2">
              <div className="text-sm font-semibold text-foreground">标记</div>
              {marks.length === 0 ? (
                <Notice>该日没有 life_events 记录。</Notice>
              ) : (
                <div className="grid gap-2">
                  {marks.map((m) => {
                    const label = (m.location_name || "").trim() || "(无标签)";
                    return (
                      <div
                        key={m.id}
                        className="rounded-2xl border border-border bg-black/20 px-4 py-3"
                      >
                        <div className="flex flex-wrap items-center justify-between gap-2">
                          <div className="text-sm font-semibold text-foreground">{label}</div>
                          <div className="rounded-full border border-white/10 bg-white/5 px-2 py-0.5 font-mono text-[11px] text-foreground/70">
                            {m.event_type}
                          </div>
                        </div>
                        <div className="mt-1 text-xs text-foreground/60">
                          {m.start_at} → {m.end_at}
                        </div>
                        {m.manual_note ? (
                          <div className="mt-2 text-xs text-foreground/70">
                            备注：{m.manual_note}
                          </div>
                        ) : null}
                        {typeof m.latitude === "number" && typeof m.longitude === "number" ? (
                          <div className="mt-2 font-mono text-xs text-foreground/70">
                            GPS：{m.latitude.toFixed(6)}, {m.longitude.toFixed(6)}
                          </div>
                        ) : null}
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          </CardBody>
        </Card>
      ) : null}
    </div>
  );
}
