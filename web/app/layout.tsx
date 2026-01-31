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
  description: "Wayfarer web console",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en">
      <body
        className={`${geistSans.variable} ${geistMono.variable} antialiased`}
      >
        <div className="min-h-dvh">
          <header className="sticky top-0 z-20 border-b border-white/10 bg-[color:var(--background)]/70 backdrop-blur">
            <div className="mx-auto flex max-w-5xl items-center justify-between px-5 py-4">
              <Link href="/" className="group inline-flex items-baseline gap-2">
                <span className="text-sm tracking-[0.2em] text-white/60">WAYFARER</span>
                <span className="text-base font-semibold text-white">Console</span>
              </Link>
              <nav className="flex items-center gap-2 text-sm">
                <Link
                  href="/login"
                  className="rounded-full px-3 py-1.5 text-white/80 hover:bg-white/10 hover:text-white"
                >
                  Login
                </Link>
                <Link
                  href="/tracks"
                  className="rounded-full px-3 py-1.5 text-white/80 hover:bg-white/10 hover:text-white"
                >
                  Tracks
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
