"use client";
import { type CSSProperties, useState } from "react";
import { Photo } from "./Icons";

/** A photo from RITM storage; if it does not load, a calm «Rasm ochilmadi» tile instead of the browser's broken icon. */
export function RemoteImg({ src, alt, className, style }: { src: string; alt: string; className?: string; style?: CSSProperties }) {
  const [bad, setBad] = useState(false);
  if (bad) {
    return (
      <div className={className} style={{ ...style, display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center", gap: 4, color: "var(--text-faint)", fontSize: 12, fontWeight: 700, background: "var(--fill-2)" }}>
        <Photo width={22} height={22} />Rasm ochilmadi
      </div>
    );
  }
  // eslint-disable-next-line @next/next/no-img-element
  return <img src={src} alt={alt} className={className} style={style} loading="lazy" onError={() => setBad(true)} />;
}
