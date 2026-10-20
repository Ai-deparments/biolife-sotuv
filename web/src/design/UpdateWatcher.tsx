"use client";
import { useEffect, useState } from "react";
import { Refresh } from "./Icons";

const BUILD = process.env.NEXT_PUBLIC_BUILD_ID ?? "dev";

/** Nothing typed and no sheet/dialog open: safe to reload without losing anything. */
function idle() {
  const a = document.activeElement;
  const typing = !!a && (a.tagName === "INPUT" || a.tagName === "TEXTAREA" || a.tagName === "SELECT" || (a as HTMLElement).isContentEditable);
  return !typing && !document.querySelector('[role="dialog"]');
}

/**
 * The open window switches to a new version by itself (the panel is used as an installed app).
 * Checks /version every minute and when the window comes back; reloads at once when idle,
 * otherwise shows «Yangilash» and reloads as soon as the person is done.
 */
export function UpdateWatcher() {
  const [pending, setPending] = useState(false);

  useEffect(() => {
    if (BUILD === "dev") return;
    let stop = false;
    let waiting: ReturnType<typeof setInterval> | undefined;
    const check = async () => {
      try {
        const r = await fetch(`/version?t=${Date.now()}`, { cache: "no-store" });
        const { build } = (await r.json()) as { build?: string };
        if (stop || !build || build === BUILD || build === "dev") return;
        if (idle()) { location.reload(); return; }
        setPending(true);
        if (!waiting) waiting = setInterval(() => { if (idle()) location.reload(); }, 4000);
      } catch { /* offline or restarting: try again later */ }
    };
    const timer = setInterval(check, 60_000);
    const onFocus = () => { if (document.visibilityState === "visible") check(); };
    document.addEventListener("visibilitychange", onFocus);
    const first = setTimeout(check, 5_000);
    return () => { stop = true; clearInterval(timer); clearTimeout(first); if (waiting) clearInterval(waiting); document.removeEventListener("visibilitychange", onFocus); };
  }, []);

  if (!pending) return null;
  return (
    <button type="button" onClick={() => location.reload()} className="lg press primary update-pill" aria-live="polite">
      <Refresh width={18} height={18} /> Yangi versiya tayyor · Yangilash
    </button>
  );
}
