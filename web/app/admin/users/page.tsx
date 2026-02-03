"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";

import { useAuth } from "@/app/providers";
import { ApiError, apiFetch } from "@/lib/api";

import { Button } from "@/components/ui/Button";
import { Card, CardBody } from "@/components/ui/Card";
import { Notice } from "@/components/ui/Notice";

type AdminUserRow = {
  user_id: string;
  username: string;
  email?: string | null;
  is_admin: boolean;
  created_at: string;
};

function formatDateTime(iso: string): string {
  const t = Date.parse(iso);
  if (!Number.isFinite(t)) return iso;
  return new Date(t).toLocaleString("zh-CN", { hour12: false });
}

function getAdminUsersErrorMessage(err: unknown, base: string): string {
  if (!(err instanceof ApiError)) return base;

  // ApiError.bodyText may contain JSON; never display it raw.
  if (!err.bodyText) return base;
  try {
    const parsed: unknown = JSON.parse(err.bodyText);
    if (!parsed || typeof parsed !== "object") return base;
    const traceId = (parsed as { trace_id?: unknown }).trace_id;
    if (typeof traceId !== "string" || !traceId.trim()) return base;
    return `${base}参考编号：${traceId.trim()}`;
  } catch {
    return base;
  }
}

export default function AdminUsersPage() {
  const router = useRouter();
  const { accessToken, isHydrating, refresh, me, isMeLoading } = useAuth();

  const [items, setItems] = useState<AdminUserRow[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [savingId, setSavingId] = useState<string | null>(null);

  const isAdmin = Boolean(me?.is_admin);

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

  const canLoad = useMemo(() => {
    if (isHydrating) return false;
    if (!accessToken) return false;
    if (isMeLoading) return false;
    return isAdmin;
  }, [accessToken, isAdmin, isHydrating, isMeLoading]);

  useEffect(() => {
    if (isHydrating) return;
    // Not logged in: bounce to login.
    if (!accessToken && !isMeLoading) {
      void ensureToken();
    }
  }, [accessToken, ensureToken, isHydrating, isMeLoading]);

  useEffect(() => {
    if (!canLoad) return;
    let cancelled = false;
    setLoading(true);
    setError(null);

    void (async () => {
      try {
        const token = await ensureToken();
        if (!token || cancelled) return;
        const data = await apiFetch<AdminUserRow[]>(
          "/v1/admin/users",
          { method: "GET" },
          { accessToken: token, refreshAccessToken: refresh },
        );
        if (!cancelled) setItems(Array.isArray(data) ? data : []);
      } catch (err) {
        if (cancelled) return;
        setError(getAdminUsersErrorMessage(err, "加载失败，请稍后再试。"));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [canLoad, ensureToken, refresh]);

  async function toggleAdmin(user: AdminUserRow) {
    setSavingId(user.user_id);
    setError(null);
    const next = !user.is_admin;

    try {
      const token = await ensureToken();
      if (!token) return;
      const updated = await apiFetch<AdminUserRow>(
        `/v1/admin/users/${encodeURIComponent(user.user_id)}/admin`,
        {
          method: "PUT",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ is_admin: next }),
        },
        { accessToken: token, refreshAccessToken: refresh },
      );
      setItems((prev) =>
        prev.map((u) => (u.user_id === updated.user_id ? updated : u)),
      );
    } catch (err) {
      setError(getAdminUsersErrorMessage(err, "更新失败，请稍后再试。"));
    } finally {
      setSavingId(null);
    }
  }

  return (
    <div className="space-y-6">
      <div>
        <div className="text-xs tracking-[0.25em] text-foreground/60">ADMIN</div>
        <h1 className="mt-2 text-2xl font-semibold text-foreground">用户管理</h1>
        <p className="mt-2 text-sm text-foreground/70">
          查看用户列表并切换管理员权限（需要管理员账号）。
        </p>
      </div>

      {!isMeLoading && !isAdmin ? (
        <Notice variant="error">无权限：当前账号不是管理员。</Notice>
      ) : null}

      {error ? (
        <Notice variant="error" data-testid="admin-users-error">
          {error}
        </Notice>
      ) : null}

      <Card>
        <CardBody className="p-6">
          <div className="flex items-center justify-between gap-3">
            <div>
              <div className="text-sm font-semibold text-foreground">用户列表</div>
              <div className="mt-1 text-xs text-foreground/60">
                共 {items.length} 人
              </div>
            </div>
            <Button
              variant="secondary"
              size="sm"
              disabled={loading || !isAdmin}
              onClick={() => {
                // Simple reload without extra abstraction.
                setItems([]);
                setError(null);
                setLoading(true);
                void (async () => {
                  try {
                    const token = await ensureToken();
                    if (!token) return;
                    const data = await apiFetch<AdminUserRow[]>(
                      "/v1/admin/users",
                      { method: "GET" },
                      { accessToken: token, refreshAccessToken: refresh },
                    );
                   setItems(Array.isArray(data) ? data : []);
                  } catch (err) {
                    setError(getAdminUsersErrorMessage(err, "加载失败，请稍后再试。"));
                  } finally {
                    setLoading(false);
                  }
                })();
              }}
            >
              {loading ? "加载中…" : "刷新"}
            </Button>
          </div>

          <div className="mt-5 space-y-3">
            {items.map((u) => (
              <div
                key={u.user_id}
                className="flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-border bg-black/20 px-4 py-3"
              >
                <div className="min-w-[220px]">
                  <div className="flex items-center gap-2">
                    <span className="text-sm font-semibold text-foreground">{u.username}</span>
                    {u.is_admin ? (
                      <span className="rounded-full border border-border bg-panel px-2 py-0.5 text-xs font-semibold text-foreground/80">
                        管理员
                      </span>
                    ) : null}
                  </div>
                  <div className="mt-1 text-xs text-foreground/60">
                    <span className="font-mono">{u.user_id}</span>
                    {u.email ? ` · ${u.email}` : " · （无邮箱）"}
                  </div>
                  <div className="mt-1 text-xs text-foreground/60">
                    创建时间：{formatDateTime(u.created_at)}
                  </div>
                </div>

                <div className="flex items-center gap-2">
                  <Button
                    variant={u.is_admin ? "danger" : "secondary"}
                    size="sm"
                    disabled={!isAdmin || savingId === u.user_id}
                    onClick={() => void toggleAdmin(u)}
                  >
                    {savingId === u.user_id
                      ? "保存中…"
                      : u.is_admin
                        ? "取消管理员"
                        : "设为管理员"}
                  </Button>
                </div>
              </div>
            ))}

            {!loading && isAdmin && items.length === 0 ? (
              <div className="rounded-2xl border border-border bg-black/20 px-4 py-6 text-sm text-foreground/70">
                暂无数据。
              </div>
            ) : null}
          </div>
        </CardBody>
      </Card>
    </div>
  );
}
