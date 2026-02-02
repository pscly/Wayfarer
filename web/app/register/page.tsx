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
      if (err instanceof ApiError) {
        setError(err.bodyText || `注册失败（${err.status}）`);
      } else if (err instanceof Error) {
        setError(err.message);
      } else {
        setError("注册失败");
      }
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
              <FieldHint className="mt-2">必填；用于登录与显示。</FieldHint>
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
              <FieldHint className="mt-2">可不填；用于找回/通知等用途。</FieldHint>
            </label>

            <label className="block">
              <FieldLabel>密码</FieldLabel>
              <Input
                data-testid="register-password"
                type="password"
                autoComplete="new-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                minLength={12}
                required
              />
              <FieldHint className="mt-2">至少 12 位；大小写敏感。</FieldHint>
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
