"use client";

import Link from "next/link";

import { useAuth } from "@/app/providers";

export function HeaderNav() {
  const { me } = useAuth();

  return (
    <nav className="flex items-center gap-2 text-sm">
      <Link
        href="/login"
        className="rounded-full px-3 py-1.5 text-foreground/80 hover:bg-panel hover:text-foreground"
      >
        登录
      </Link>
      <Link
        href="/tracks"
        className="rounded-full px-3 py-1.5 text-foreground/80 hover:bg-panel hover:text-foreground"
      >
        轨迹
      </Link>
      {me?.is_admin ? (
        <Link
          href="/admin/users"
          className="rounded-full px-3 py-1.5 text-foreground/80 hover:bg-panel hover:text-foreground"
        >
          用户管理
        </Link>
      ) : null}
    </nav>
  );
}
