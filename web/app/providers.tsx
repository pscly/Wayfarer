"use client";

import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from "react";
import * as auth from "@/lib/auth";
import { ApiError, apiFetch } from "@/lib/api";

type AuthContextValue = {
  accessToken: string | null;
  isHydrating: boolean;
  me: auth.UserInfo | null;
  isMeLoading: boolean;

  login: (username: string, password: string) => Promise<void>;
  refresh: () => Promise<string | null>;
  reloadMe: () => Promise<auth.UserInfo | null>;
  logout: () => Promise<void>;
  setAccessToken: (token: string | null) => void;
};

const AuthContext = createContext<AuthContextValue | null>(null);

const SESSION_KEY = "wf_access_token";

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within <Providers>");
  return ctx;
}

export function Providers({ children }: { children: React.ReactNode }) {
  const [accessToken, setAccessTokenState] = useState<string | null>(null);
  const [isHydrating, setIsHydrating] = useState(true);
  const [me, setMe] = useState<auth.UserInfo | null>(null);
  const [isMeLoading, setIsMeLoading] = useState(false);

  const setAccessToken = useCallback((token: string | null) => {
    setAccessTokenState(token);
    if (!token) setMe(null);
    try {
      if (token) sessionStorage.setItem(SESSION_KEY, token);
      else sessionStorage.removeItem(SESSION_KEY);
    } catch {
      // ignore
    }
  }, []);

  useEffect(() => {
      // Best-effort: restore access token for faster page loads.
      try {
        const saved = sessionStorage.getItem(SESSION_KEY);
        if (saved) setAccessTokenState(saved);
      } catch {
        // ignore
      } finally {
        setIsHydrating(false);
      }
  }, []);

  const refreshAccessToken = useCallback(async (): Promise<string | null> => {
    const token = await auth.refresh();
    if (token) setAccessToken(token);
    return token;
  }, [setAccessToken]);

  const loadMe = useCallback(
    async (token: string): Promise<auth.UserInfo | null> => {
      setIsMeLoading(true);
      try {
        return await apiFetch<auth.UserInfo>(
          "/v1/users/me",
          { method: "GET" },
          { accessToken: token, refreshAccessToken },
        );
      } catch (err) {
        if (err instanceof ApiError && err.status === 401) {
          // Token is invalid and refresh didn't help; clear local session.
          setMe(null);
          setAccessToken(null);
          return null;
        }
        throw err;
      } finally {
        setIsMeLoading(false);
      }
    },
    [refreshAccessToken, setAccessToken],
  );

  useEffect(() => {
    if (isHydrating) return;
    if (!accessToken) {
      setMe(null);
      return;
    }

    let cancelled = false;
    void (async () => {
      try {
        const next = await loadMe(accessToken);
        if (!cancelled) setMe(next);
      } catch {
        // Best-effort only; pages can surface their own errors.
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [accessToken, isHydrating, loadMe]);

  const value = useMemo<AuthContextValue>(() => {
    return {
      accessToken,
      isHydrating,
      me,
      isMeLoading,
      setAccessToken,
      login: async (username, password) => {
        const token = await auth.login({ username, password });
        setAccessToken(token);
      },
      refresh: refreshAccessToken,
      reloadMe: async () => {
        if (isHydrating) return null;
        const token = accessToken;
        if (!token) return null;
        const next = await loadMe(token);
        setMe(next);
        return next;
      },
      logout: async () => {
        await auth.logout();
        setMe(null);
        setAccessToken(null);
      },
    };
  }, [accessToken, isHydrating, isMeLoading, me, loadMe, refreshAccessToken, setAccessToken]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
