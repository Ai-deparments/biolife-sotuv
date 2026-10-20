"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import { newId, pickMime, saveChunk, saveRec, type LocalRec } from "./recStore";

export const MAX_SEC = 30 * 60;

export type RecorderStatus = "idle" | "starting" | "recording" | "saving" | "denied" | "unsupported";

/**
 * Records one shop conversation: microphone → MediaRecorder (1 s slices) → IndexedDB as it goes.
 * Keeps the screen on, stops by itself at 30 minutes or if the microphone is taken away, and hands the saved id to `onSaved`.
 */
export function useConversationRecorder(owner: string | undefined, onSaved: (id: string) => void) {
  const [status, setStatus] = useState<RecorderStatus>("idle");
  const [seconds, setSeconds] = useState(0);
  const [levels, setLevels] = useState<number[]>(() => Array(16).fill(0.08));
  const [error, setError] = useState<string>();
  const onSavedRef = useRef(onSaved);
  useEffect(() => { onSavedRef.current = onSaved; });
  const rec = useRef<MediaRecorder | null>(null);
  const stream = useRef<MediaStream | null>(null);
  const ctx = useRef<AudioContext | null>(null);
  const raf = useRef(0);
  const timer = useRef<ReturnType<typeof setInterval> | undefined>(undefined);
  const lock = useRef<WakeLockSentinel | null>(null);
  const active = useRef(false);

  const keepAwake = useCallback(async () => {
    try { if ("wakeLock" in navigator && !lock.current) lock.current = await navigator.wakeLock.request("screen"); } catch { /* not allowed: the hint asks to keep the screen on */ }
  }, []);

  const release = useCallback(() => {
    active.current = false;
    cancelAnimationFrame(raf.current);
    clearInterval(timer.current);
    stream.current?.getTracks().forEach((t) => t.stop());
    stream.current = null;
    ctx.current?.close().catch(() => {});
    ctx.current = null;
    lock.current?.release().catch(() => {});
    lock.current = null;
  }, []);

  const stop = useCallback(() => {
    const r = rec.current;
    if (r && r.state !== "inactive") { setStatus("saving"); r.stop(); }
  }, []);

  const start = useCallback(async () => {
    setError(undefined);
    if (!owner) return;
    if (typeof window === "undefined" || !navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === "undefined" || !("indexedDB" in window)) {
      setStatus("unsupported");
      return;
    }
    setStatus("starting");
    let s: MediaStream;
    try {
      s = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: false, noiseSuppression: false, autoGainControl: true } });
    } catch (e) {
      setStatus("denied");
      setError(e instanceof Error ? e.name : String(e));
      return;
    }
    stream.current = s;
    const mime = pickMime();
    let r: MediaRecorder;
    try {
      r = new MediaRecorder(s, { ...(mime ? { mimeType: mime } : {}), audioBitsPerSecond: 32_000 });
    } catch {
      r = new MediaRecorder(s);
    }
    const id = newId();
    const t0 = Date.now();
    const base: LocalRec = { owner, id, startedAt: new Date(t0).toISOString(), mime: mime || "audio/webm", state: "recording", tries: 0, updatedAt: t0 };
    let seq = 0;
    let writes: Promise<unknown> = saveRec(base);
    r.ondataavailable = (e) => {
      if (!e.data || e.data.size === 0) return;
      const n = seq++;
      const at = Date.now();
      writes = writes.then(() => saveChunk({ id, seq: n, at, data: e.data })).then(() => saveRec({ ...base, mime: r.mimeType || base.mime, updatedAt: at })).catch(() => {});
    };
    r.onstop = () => {
      const end = Date.now();
      writes.then(async () => {
        const sec = Math.min(MAX_SEC, Math.max(1, Math.round((end - t0) / 1000)));
        await saveRec({ ...base, mime: r.mimeType || base.mime, state: "ready", endedAt: new Date(t0 + sec * 1000).toISOString(), durationSec: sec, updatedAt: Date.now() });
        release();
        setStatus("idle");
        setSeconds(0);
        onSavedRef.current(id);
      }).catch(() => { release(); setStatus("idle"); setError("Yozuv telefonda saqlanmadi"); });
    };
    s.getAudioTracks().forEach((t) => { t.onended = () => stop(); });
    rec.current = r;
    r.start(1000);
    active.current = true;
    setSeconds(0);
    setStatus("recording");
    keepAwake();
    timer.current = setInterval(() => {
      const sec = Math.floor((Date.now() - t0) / 1000);
      setSeconds(sec);
      if (sec >= MAX_SEC) stop();
    }, 500);
    try {
      const ac = new AudioContext();
      ctx.current = ac;
      const an = ac.createAnalyser();
      an.fftSize = 64;
      ac.createMediaStreamSource(s).connect(an);
      const data = new Uint8Array(an.frequencyBinCount);
      const tick = () => {
        an.getByteFrequencyData(data);
        const step = Math.max(1, Math.floor(data.length / 16));
        setLevels(Array.from({ length: 16 }, (_, i) => Math.max(0.08, data[i * step] / 255)));
        raf.current = requestAnimationFrame(tick);
      };
      tick();
    } catch { /* the meter is only a hint that the microphone hears */ }
  }, [owner, keepAwake, release, stop]);

  // screen lock is released when the page hides; take it back when the agent returns
  useEffect(() => {
    const vis = () => { if (document.visibilityState === "visible" && active.current) keepAwake(); };
    const leave = (e: BeforeUnloadEvent) => { if (active.current) e.preventDefault(); };
    document.addEventListener("visibilitychange", vis);
    window.addEventListener("beforeunload", leave);
    return () => { document.removeEventListener("visibilitychange", vis); window.removeEventListener("beforeunload", leave); };
  }, [keepAwake]);

  // leaving the screen ends the recording properly (what was said is kept)
  useEffect(() => () => { if (rec.current && rec.current.state !== "inactive") rec.current.stop(); }, []);

  return { status, seconds, levels, error, start, stop };
}
