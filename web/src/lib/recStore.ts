"use client";
/**
 * Phone-side safety net for conversation recordings. Every second of audio is written to IndexedDB while it is
 * recorded, so a closed tab, a phone that died or no internet never loses a conversation. Finished recordings are
 * uploaded with the id made here (the API ignores a repeat), and stay on the phone until the API has them.
 */

export interface LocalRec {
  /** the agent who recorded it: a phone shared by two agents never sends one agent's conversation under the other's login */
  owner: string;
  id: string; startedAt: string; endedAt?: string; durationSec?: number; mime: string;
  state: "recording" | "ready" | "failed"; error?: string; tries: number; size?: number; updatedAt: number;
}
interface Chunk { id: string; seq: number; at: number; data: Blob }

const MAX_SEC = 30 * 60;
let dbp: Promise<IDBDatabase> | null = null;

function db(): Promise<IDBDatabase> {
  if (!dbp) {
    dbp = new Promise((resolve, reject) => {
      const r = indexedDB.open("biolife-rec", 1);
      r.onupgradeneeded = () => {
        const d = r.result;
        if (!d.objectStoreNames.contains("rec")) d.createObjectStore("rec", { keyPath: "id" });
        if (!d.objectStoreNames.contains("chunk")) d.createObjectStore("chunk", { keyPath: ["id", "seq"] });
      };
      r.onsuccess = () => resolve(r.result);
      r.onerror = () => { dbp = null; reject(r.error); };
    });
  }
  return dbp;
}

async function run<T>(store: string, mode: IDBTransactionMode, op: (s: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  const d = await db();
  return new Promise<T>((resolve, reject) => {
    const t = d.transaction(store, mode);
    const req = op(t.objectStore(store));
    t.oncomplete = () => resolve(req.result);
    t.onerror = () => reject(t.error);
    t.onabort = () => reject(t.error);
  });
}

const chunkRange = (id: string) => IDBKeyRange.bound([id, 0], [id, Number.MAX_SAFE_INTEGER]);

export const saveRec = (r: LocalRec) => run("rec", "readwrite", (s) => s.put(r));
export const listRecs = () => run<LocalRec[]>("rec", "readonly", (s) => s.getAll());
export const saveChunk = (c: Chunk) => run("chunk", "readwrite", (s) => s.put(c));
const chunksOf = (id: string) => run<Chunk[]>("chunk", "readonly", (s) => s.getAll(chunkRange(id)));

export async function dropRec(id: string): Promise<void> {
  const d = await db();
  await new Promise<void>((resolve, reject) => {
    const t = d.transaction(["rec", "chunk"], "readwrite");
    t.objectStore("rec").delete(id);
    t.objectStore("chunk").delete(chunkRange(id));
    t.oncomplete = () => resolve();
    t.onerror = () => reject(t.error);
  });
}

/** Browser recording format, best first: Android Chrome → webm/opus, iPhone Safari → mp4/aac. */
export function pickMime(): string {
  if (typeof MediaRecorder === "undefined" || !MediaRecorder.isTypeSupported) return "";
  return ["audio/webm;codecs=opus", "audio/mp4", "audio/ogg;codecs=opus", "audio/webm"].find((m) => MediaRecorder.isTypeSupported(m)) ?? "";
}

export function newId(): string {
  if (typeof crypto.randomUUID === "function") return crypto.randomUUID();
  const b = crypto.getRandomValues(new Uint8Array(16));
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = Array.from(b, (x) => x.toString(16).padStart(2, "0")).join("");
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

const ext = (mime: string) => (mime.includes("mp4") || mime.includes("aac") ? "m4a" : mime.includes("ogg") ? "ogg" : "webm");

/**
 * A recording left in "recording" state (tab closed, phone restarted) is finished from what was saved.
 * `quietMs` keeps a recording that another tab is still writing (its chunks arrive every second) untouched.
 */
export async function recoverInterrupted(quietMs = 15_000): Promise<number> {
  let n = 0;
  for (const r of await listRecs()) {
    if (r.state !== "recording" || Date.now() - r.updatedAt < quietMs) continue;
    const chunks = await chunksOf(r.id);
    if (chunks.length === 0) { await dropRec(r.id); continue; }
    const last = Math.max(...chunks.map((c) => c.at));
    const sec = Math.min(MAX_SEC, Math.max(1, Math.round((last - Date.parse(r.startedAt)) / 1000)));
    await saveRec({ ...r, state: "ready", endedAt: new Date(Date.parse(r.startedAt) + sec * 1000).toISOString(), durationSec: sec, updatedAt: Date.now() });
    n++;
  }
  return n;
}

export type UploadResult = "ok" | "retry" | "failed" | "auth";

/** One upload attempt. "auth": the login expired — the recording waits on the phone until the agent logs in again. */
export async function uploadRec(r: LocalRec, token: string): Promise<UploadResult> {
  const chunks = await chunksOf(r.id);
  if (chunks.length === 0) { await dropRec(r.id); return "failed"; }
  const blob = new Blob(chunks.map((c) => c.data), { type: r.mime });
  const fd = new FormData();
  fd.append("file", blob, `suhbat-${r.id}.${ext(r.mime)}`);
  fd.append("id", r.id);
  fd.append("startedAt", r.startedAt);
  fd.append("endedAt", r.endedAt ?? new Date(Date.parse(r.startedAt) + (r.durationSec ?? 1) * 1000).toISOString());
  fd.append("durationSec", String(r.durationSec ?? 1));
  let res: Response;
  try {
    res = await fetch("/api/v1/me/recordings", { method: "POST", headers: { Authorization: `Bearer ${token}` }, body: fd });
  } catch {
    await saveRec({ ...r, tries: r.tries + 1, error: "Internet yo'q", size: blob.size, updatedAt: Date.now() });
    return "retry";
  }
  if (res.ok) { await dropRec(r.id); return "ok"; }
  if (res.status === 401) return "auth";
  if (res.status >= 500 || res.status === 408 || res.status === 429 || res.status === 404) {
    await saveRec({ ...r, tries: r.tries + 1, error: `Server javob bermadi (${res.status})`, size: blob.size, updatedAt: Date.now() });
    return "retry";
  }
  let detail = "Yuborib bo'lmadi";
  try { detail = (await res.json()).detail ?? detail; } catch { /* not json */ }
  await saveRec({ ...r, state: "failed", tries: r.tries + 1, error: detail, size: blob.size, updatedAt: Date.now() });
  return "failed";
}

let pumping: Promise<{ uploaded: number; auth: boolean }> | null = null;

/** Sends every finished recording, oldest first. One run at a time; stops at the first network failure (tries later). */
export function pumpUploads(token: string, owner: string): Promise<{ uploaded: number; auth: boolean }> {
  if (pumping) return pumping;
  pumping = (async () => {
    let uploaded = 0;
    let auth = false;
    try {
      await recoverInterrupted();
      const ready = (await listRecs()).filter((r) => r.state === "ready" && r.owner === owner).sort((a, b) => a.startedAt.localeCompare(b.startedAt));
      for (const r of ready) {
        const res = await uploadRec(r, token);
        if (res === "ok") uploaded++;
        else if (res === "auth") { auth = true; break; }
        else if (res === "retry") break;
      }
    } finally {
      pumping = null;
    }
    return { uploaded, auth };
  })();
  return pumping;
}
