"use client";
import { createContext, useCallback, useContext, useEffect, useMemo, useSyncExternalStore } from "react";

/** Tong = morning/light silk, Tun = night/dark silk. Auto follows the clock (9–18) and the system scheme. */
export type Mode = "tong" | "tun";
type Pref = Mode | "auto";
const KEY = "biolife.theme";

/** Light by default; dark only when the person chose it (the toggle). */
function autoMode(): Mode { return "tong"; }

/* Tiny external stores so hydration stays clean (server renders "auto"/"tong", client re-syncs). */
const prefListeners = new Set<() => void>();
const readPref = (): Pref => { try { return (localStorage.getItem(KEY) as Pref | null) ?? "auto"; } catch { return "auto"; } };
const subscribePref = (l: () => void) => { prefListeners.add(l); window.addEventListener("storage", l); return () => { prefListeners.delete(l); window.removeEventListener("storage", l); }; };
const subscribeClock = () => () => {};

const Ctx = createContext<{ mode: Mode; pref: Pref; setPref: (p: Pref) => void }>({ mode: "tong", pref: "auto", setPref: () => {} });

export function ThemeProvider({ children }: { children: React.ReactNode }) {
  const pref = useSyncExternalStore(subscribePref, readPref, () => "auto" as Pref);
  const auto = useSyncExternalStore(subscribeClock, autoMode, () => "tong" as Mode);
  const mode: Mode = pref === "auto" ? auto : pref;

  useEffect(() => {
    document.documentElement.dataset.theme = mode;
    document.querySelector('meta[name="theme-color"]')?.setAttribute("content", mode === "tun" ? "#0a1430" : "#dbe8ff");
  }, [mode]);

  const setPref = useCallback((p: Pref) => { try { localStorage.setItem(KEY, p); } catch { /* ignore */ } prefListeners.forEach((l) => l()); }, []);
  const value = useMemo(() => ({ mode, pref, setPref }), [mode, pref, setPref]);
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}
export const useTheme = () => useContext(Ctx);
