import { useId } from "react";

/** BioLife mark — the same drop-and-leaf sign as in every NOVA-KIT system. */
export function BioLifeMark({ size = 36 }: { size?: number }) {
  const gid = `bl-${useId().replace(/:/g, "")}`;
  return (
    <svg width={size} height={size} viewBox="0 0 32 32" role="img" aria-label="BioLife" style={{ flexShrink: 0, display: "block" }}>
      <defs>
        <linearGradient id={gid} x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#0f7ea8" />
          <stop offset="1" stopColor="#2a9d5c" />
        </linearGradient>
      </defs>
      <rect width="32" height="32" rx="9" fill={`url(#${gid})`} />
      <path d="M16 6.2c4.2 4.6 6.3 7.9 6.3 10.9a6.3 6.3 0 0 1-12.6 0c0-3 2.1-6.3 6.3-10.9Z" fill="#fff" />
      <path d="M16 22.9v-8M16 17.3l2.5-1.9M16 19.7l-2.5-1.9" stroke={`url(#${gid})`} strokeWidth="1.5" strokeLinecap="round" fill="none" />
    </svg>
  );
}

/** Mark + «BioLife / Agent AI». */
export function BrandLockup({ size = "md" }: { size?: "md" | "lg" }) {
  const lg = size === "lg";
  return (
    <span style={{ display: "inline-flex", alignItems: "center", gap: lg ? 14 : 11 }}>
      <BioLifeMark size={lg ? 52 : 38} />
      <span style={{ lineHeight: 1.1 }}>
        <span style={{ display: "block", fontSize: lg ? 24 : 18, fontWeight: 800, letterSpacing: "-0.02em" }}>BioLife</span>
        <span className="lbl dim" style={{ display: "block", marginTop: 3 }}>Agent AI · rahbar paneli</span>
      </span>
    </span>
  );
}
