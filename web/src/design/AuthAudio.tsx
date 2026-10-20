"use client";
import { useCallback, useEffect, useState, type Ref, type SyntheticEvent } from "react";
import { getSession } from "@/data/api";

/**
 * Player for a protected recording: fetched with the login token (a plain src cannot send it) and played from memory.
 * Chrome's own recordings (webm from the phone) carry no length, so the player could not jump to a quoted moment:
 * it skips to the end once to learn the length, then returns to the start.
 */
export function AuthAudio({ path, ref }: { path: string; ref?: Ref<HTMLAudioElement> }) {
  const [state, setState] = useState<{ path?: string; url?: string; error?: string }>({});
  useEffect(() => {
    let url: string | undefined;
    let alive = true;
    const s = getSession();
    fetch(`/api/v1${path}`, { headers: s ? { Authorization: `Bearer ${s.accessToken}` } : {}, cache: "no-store" })
      .then(async (r) => {
        if (!r.ok) throw new Error(r.status === 404 ? "Ovoz fayli topilmadi: 180 kundan eski yozuvlarning ovozi o'chiriladi, matni qoladi." : `Ovoz yuklanmadi (${r.status})`);
        return r.blob();
      })
      .then((b) => { if (!alive) return; url = URL.createObjectURL(b); setState({ path, url }); })
      .catch((e: unknown) => { if (alive) setState({ path, error: e instanceof Error ? e.message : String(e) }); });
    return () => { alive = false; if (url) URL.revokeObjectURL(url); };
  }, [path]);

  const setRef = useCallback((el: HTMLAudioElement | null) => {
    if (typeof ref === "function") ref(el);
    else if (ref) ref.current = el;
  }, [ref]);

  const learnLength = (e: SyntheticEvent<HTMLAudioElement>) => {
    const a = e.currentTarget;
    if (Number.isFinite(a.duration)) return;
    const back = () => { a.removeEventListener("timeupdate", back); a.currentTime = 0; };
    a.addEventListener("timeupdate", back);
    a.currentTime = 1e101;
  };

  if (state.path !== path) return <div className="note">Ovoz yuklanmoqda…</div>;
  if (state.error) return <div className="note">{state.error}</div>;
  return <audio ref={setRef} controls preload="metadata" src={state.url} onLoadedMetadata={learnLength} style={{ width: "100%", height: 44 }} />;
}
