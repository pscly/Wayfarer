import { API_BASE_URL, ApiError } from "@/lib/api";

export type LoginRequest = {
  email: string;
  password: string;
};

export type TokenResponse = {
  access_token: string;
};

export function readCookie(name: string): string | null {
  if (typeof document === "undefined") return null;
  const cookies = document.cookie ? document.cookie.split(";") : [];
  for (const part of cookies) {
    const [k, ...rest] = part.trim().split("=");
    if (k === name) return decodeURIComponent(rest.join("="));
  }
  return null;
}

export function readCsrfToken(): string | null {
  return readCookie("wf_csrf");
}

function joinUrl(base: string, path: string): string {
  if (path.startsWith("http://") || path.startsWith("https://")) return path;
  const b = base.endsWith("/") ? base.slice(0, -1) : base;
  const p = path.startsWith("/") ? path : `/${path}`;
  return `${b}${p}`;
}

async function readBodyText(res: Response): Promise<string | undefined> {
  try {
    const text = await res.text();
    return text || undefined;
  } catch {
    return undefined;
  }
}

export async function login(req: LoginRequest): Promise<string> {
  const res = await fetch(joinUrl(API_BASE_URL, "/v1/auth/login"), {
    method: "POST",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
    },
    body: JSON.stringify(req),
    credentials: "include",
  });

  if (!res.ok) {
    const bodyText = await readBodyText(res);
    throw new ApiError("Login failed", res.status, bodyText);
  }

  const data = (await res.json()) as TokenResponse;
  if (!data?.access_token) throw new Error("Login response missing access_token");
  return data.access_token;
}

export async function refresh(): Promise<string | null> {
  const csrf = readCsrfToken();
  const headers: Record<string, string> = {
    Accept: "application/json",
  };
  if (csrf) headers["X-CSRF-Token"] = csrf;

  const res = await fetch(joinUrl(API_BASE_URL, "/v1/auth/refresh"), {
    method: "POST",
    headers,
    credentials: "include",
  });

  if (res.status === 401 || res.status === 403) return null;
  if (!res.ok) {
    const bodyText = await readBodyText(res);
    throw new ApiError("Refresh failed", res.status, bodyText);
  }

  const data = (await res.json()) as TokenResponse;
  return data?.access_token || null;
}

export async function logout(): Promise<void> {
  // Backend endpoint is optional; local state should always be cleared.
  try {
    await fetch(joinUrl(API_BASE_URL, "/v1/auth/logout"), {
      method: "POST",
      headers: { Accept: "application/json" },
      credentials: "include",
    });
  } catch {
    // ignore network errors for logout
  }
}
