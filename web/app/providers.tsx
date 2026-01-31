"use client";

import React, { createContext, useContext, useEffect, useMemo, useState } from "react";
import * as auth from "@/lib/auth";

type AuthContextValue = {
  accessToken: string | null;
  isHydrating: boolean;
  login: (email: string, password: string) => Promise<void>;
  refresh: () => Promise<string | null>;
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
  const [accessToken, setAccessToken] = useState<string | null>(null);
  const [isHydrating, setIsHydrating] = useState(true);

  useEffect(() => {
    // Best-effort: restore access token for faster page loads.
    try {
      const saved = sessionStorage.getItem(SESSION_KEY);
      if (saved) setAccessToken(saved);
    } catch {
      // ignore
    } finally {
      setIsHydrating(false);
    }
  }, []);

  const value = useMemo<AuthContextValue>(() => {
    return {
      accessToken,
      isHydrating,
      setAccessToken: (token) => {
        setAccessToken(token);
        try {
          if (token) sessionStorage.setItem(SESSION_KEY, token);
          else sessionStorage.removeItem(SESSION_KEY);
        } catch {
          // ignore
        }
      },
      login: async (email, password) => {
        const token = await auth.login({ email, password });
        setAccessToken(token);
        try {
          sessionStorage.setItem(SESSION_KEY, token);
        } catch {
          // ignore
        }
      },
      refresh: async () => {
        const token = await auth.refresh();
        if (token) {
          setAccessToken(token);
          try {
            sessionStorage.setItem(SESSION_KEY, token);
          } catch {
            // ignore
          }
        }
        return token;
      },
      logout: async () => {
        await auth.logout();
        setAccessToken(null);
        try {
          sessionStorage.removeItem(SESSION_KEY);
        } catch {
          // ignore
        }
      },
    };
  }, [accessToken, isHydrating]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
