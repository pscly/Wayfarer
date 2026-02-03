"use client";

import { useAuth } from "@/app/providers";
import { ApiError } from "@/lib/api";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";

import { Button } from "@/components/ui/Button";
import { Card, CardBody } from "@/components/ui/Card";
import { FieldHint, FieldLabel } from "@/components/ui/Field";
import { Input } from "@/components/ui/Input";
import { Notice } from "@/components/ui/Notice";

function getLoginErrorMessage(err: unknown): string {
  const base = "登录失败，请稍后再试。";

  if (!(err instanceof ApiError)) return base;
  if (err.status === 401) return "用户名或密码错误";

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

export default function LoginPage() {
  const router = useRouter();
  const { accessToken, isHydrating, login } = useAuth();

  const [username, setUsername] = useState("");
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
      await login(username, password);
      router.replace("/tracks");
    } catch (err) {
      setError(getLoginErrorMessage(err));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="mx-auto w-full max-w-md">
        <Card>
          <CardBody className="p-8">
            <h1 className="text-2xl font-semibold text-foreground">登录</h1>
            <p className="mt-2 text-sm text-foreground/70">
              使用后端账号密码登录。刷新依赖
          <code className="mx-1 rounded bg-white/10 px-1 py-0.5 text-xs">
            wf_csrf
          </code>
            cookie。
          </p>

        <form onSubmit={onSubmit} className="mt-6 space-y-4">
          <label className="block">
            <FieldLabel>用户名</FieldLabel>
            <Input
              data-testid="login-username"
              type="text"
              autoComplete="username"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              placeholder="yourname"
              required
            />
          </label>

          <label className="block">
            <FieldLabel>密码</FieldLabel>
            <Input
              data-testid="login-password"
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
            />
            <FieldHint className="mt-2">大小写敏感</FieldHint>
          </label>

          {error ? (
            <Notice data-testid="login-error" variant="error">
              {error}
            </Notice>
          ) : null}

          <Button
            data-testid="login-submit"
            type="submit"
            disabled={submitting}
            className="w-full rounded-xl"
            variant="primary"
          >
            {submitting ? "登录中…" : "登录"}
          </Button>

          <div className="pt-2 text-center text-sm text-foreground/70">
            还没有账号？{" "}
            <Link
              href="/register"
              className="text-foreground underline underline-offset-4 hover:text-foreground/90"
            >
              去注册
            </Link>
          </div>
        </form>
        </CardBody>
      </Card>
    </div>
  );
}
