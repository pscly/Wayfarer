import type { Metadata } from "next";
import localFont from "next/font/local";
import "./globals.css";
import Link from "next/link";
import { Providers } from "@/app/providers";
import { HeaderNav } from "@/app/HeaderNav";

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
        <Providers>
          <div className="min-h-dvh">
            <header className="sticky top-0 z-20 border-b border-border bg-[color:var(--background)]/70 backdrop-blur">
              <div className="mx-auto flex max-w-5xl items-center justify-between px-5 py-4">
                <Link href="/" className="group inline-flex items-baseline gap-2">
                  <span className="text-sm tracking-[0.2em] text-foreground/60">WAYFARER</span>
                  <span className="text-base font-semibold text-foreground">控制台</span>
                </Link>
                <HeaderNav />
              </div>
            </header>

            <main className="mx-auto w-full max-w-5xl px-5 py-10">
              {children}
            </main>
          </div>
        </Providers>
      </body>
    </html>
  );
}
