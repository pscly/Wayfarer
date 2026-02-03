"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";

import { useAuth } from "@/app/providers";
import * as auth from "@/lib/auth";
import { ApiError } from "@/lib/api";

import { Button } from "@/components/ui/Button";
import { Card, CardBody } from "@/components/ui/Card";
import { FieldHint, FieldLabel } from "@/components/ui/Field";
import { Input } from "@/components/ui/Input";
import { Notice } from "@/components/ui/Notice";

function getRegisterErrorMessage(err: unknown): string {
  const base = "注册失败，请稍后再试。";

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

export default function RegisterPage() {
  const router = useRouter();
  const { accessToken, isHydrating, login } = useAuth();

  const [username, setUsername] = useState("");
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
      await auth.register({
        username,
        email: email.trim() ? email.trim() : null,
        password,
      });

      await login(username, password);
      router.replace("/tracks");
    } catch (err) {
      setError(getRegisterErrorMessage(err));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="mx-auto w-full max-w-md">
      <Card>
        <CardBody className="p-8">
          <h1 className="text-2xl font-semibold text-foreground">注册</h1>
          <p className="mt-2 text-sm text-foreground/70">创建一个新的后端账号。</p>

          <form onSubmit={onSubmit} className="mt-6 space-y-4">
            <label className="block">
              <FieldLabel>用户名</FieldLabel>
              <Input
                data-testid="register-username"
                type="text"
                autoComplete="username"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                placeholder="alice"
                required
              />
            </label>

            <label className="block">
              <FieldLabel>邮箱（可选）</FieldLabel>
              <Input
                data-testid="register-email"
                type="email"
                autoComplete="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="you@example.com"
              />
            </label>

            <label className="block">
              <FieldLabel>密码</FieldLabel>
              <Input
                data-testid="register-password"
                type="password"
                autoComplete="new-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
              />
              <FieldHint className="mt-2">大小写敏感</FieldHint>
            </label>

            {error ? (
              <Notice data-testid="register-error" variant="error">
                {error}
              </Notice>
            ) : null}

            <Button
              data-testid="register-submit"
              type="submit"
              disabled={submitting}
              className="w-full rounded-xl"
              variant="primary"
            >
              {submitting ? "注册中…" : "注册并登录"}
            </Button>

            <div className="pt-2 text-center text-sm text-foreground/70">
              已有账号？{" "}
              <Link
                href="/login"
                className="text-foreground underline underline-offset-4 hover:text-foreground/90"
              >
                去登录
              </Link>
            </div>
          </form>
        </CardBody>
      </Card>
    </div>
  );
}
