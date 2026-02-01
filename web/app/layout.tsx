import type { Metadata } from "next";
import localFont from "next/font/local";
import "./globals.css";
import Link from "next/link";
import { Providers } from "@/app/providers";

const geistSans = localFont({
  src: "./fonts/GeistVF.woff",
  variable: "--font-geist-sans",
  weight: "100 900",
});
const geistMono = localFont({
  src: "./fonts/GeistMonoVF.woff",
  variable: "--font-geist-mono",
  weight: "100 900",
});

export const metadata: Metadata = {
  title: "Wayfarer",
  description: "Wayfarer 控制台（中文优先）",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="zh-CN">
      <body
        className={`${geistSans.variable} ${geistMono.variable} antialiased`}
      >
        <div className="min-h-dvh">
          <header className="sticky top-0 z-20 border-b border-border bg-[color:var(--background)]/70 backdrop-blur">
            <div className="mx-auto flex max-w-5xl items-center justify-between px-5 py-4">
              <Link href="/" className="group inline-flex items-baseline gap-2">
                <span className="text-sm tracking-[0.2em] text-foreground/60">WAYFARER</span>
                <span className="text-base font-semibold text-foreground">控制台</span>
              </Link>
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
              </nav>
            </div>
          </header>

          <main className="mx-auto w-full max-w-5xl px-5 py-10">
            <Providers>{children}</Providers>
          </main>
        </div>
      </body>
    </html>
  );
}
