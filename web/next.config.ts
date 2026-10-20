import type { NextConfig } from "next";

const API = process.env.BIOLIFE_API_URL ?? "http://localhost:8080";

const nextConfig: NextConfig = {
  // Self-contained server (.next/standalone) — build.sh copies it into app/web-<stamp> and switches atomically.
  output: "standalone",
  poweredByHeader: false,
  // Next buffers a proxied request body (10 MB by default, then cuts it): a 30-minute conversation recording must
  // reach the API whole. The API itself accepts up to 60 MB per file, 64 MB per request.
  experimental: { proxyClientMaxBodySize: "70mb" },
  async rewrites() {
    return {
      beforeFiles: [],
      afterFiles: [],
      // Proxy the API same-origin. As a *fallback* it runs after dynamic routes, so app/api/v1/internal/[...slug]
      // answers 404 first: service-only paths are never reachable from the internet.
      fallback: [{ source: "/api/:path*", destination: `${API}/api/:path*` }],
    };
  },
  async headers() {
    return [{
      source: "/:path*",
      headers: [
        { key: "X-Content-Type-Options", value: "nosniff" },
        { key: "X-Frame-Options", value: "DENY" },
        { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
        { key: "Permissions-Policy", value: "camera=(self), microphone=(self), geolocation=(self)" },
      ],
    }];
  },
};

export default nextConfig;
