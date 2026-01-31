export const API_BASE_URL =
  process.env.NEXT_PUBLIC_API_BASE_URL?.trim() || "http://localhost:8000";

export class ApiError extends Error {
  status: number;
  bodyText?: string;

  constructor(message: string, status: number, bodyText?: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.bodyText = bodyText;
  }
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

export type ApiFetchOptions = {
  accessToken?: string | null;
  // Called on 401 to obtain a fresh access token; apiFetch retries once.
  refreshAccessToken?: () => Promise<string | null>;
};

export async function apiFetch<T>(
  path: string,
  init: RequestInit = {},
  opts: ApiFetchOptions = {},
): Promise<T> {
  const url = joinUrl(API_BASE_URL, path);
  const headers = new Headers(init.headers);
  headers.set("Accept", "application/json");
  if (opts.accessToken) headers.set("Authorization", `Bearer ${opts.accessToken}`);

  const res = await fetch(url, {
    ...init,
    headers,
    // Web auth relies on cookies (refresh + CSRF) so credentials MUST be included.
    credentials: "include",
  });

  if (res.status === 401 && opts.refreshAccessToken) {
    const refreshed = await opts.refreshAccessToken();
    if (refreshed) {
      const retryHeaders = new Headers(init.headers);
      retryHeaders.set("Accept", "application/json");
      retryHeaders.set("Authorization", `Bearer ${refreshed}`);
      const retryRes = await fetch(url, {
        ...init,
        headers: retryHeaders,
        credentials: "include",
      });
      if (!retryRes.ok) {
        const bodyText = await readBodyText(retryRes);
        throw new ApiError(
          `API ${retryRes.status} for ${path}`,
          retryRes.status,
          bodyText,
        );
      }
      if (retryRes.status === 204) return undefined as T;
      return (await retryRes.json()) as T;
    }
  }

  if (!res.ok) {
    const bodyText = await readBodyText(res);
    throw new ApiError(`API ${res.status} for ${path}`, res.status, bodyText);
  }
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}
