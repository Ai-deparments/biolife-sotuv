/** BioLife mark (shared by all NOVA-KIT systems): a drop (water, life) with a leaf vein (bio). */
export const BRAND_1 = "#0f7ea8";
export const BRAND_2 = "#2a9d5c";

export function brandSvg(size = 512, radius = 9): string {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 32 32"><defs><linearGradient id="b" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="${BRAND_1}"/><stop offset="1" stop-color="${BRAND_2}"/></linearGradient></defs><rect width="32" height="32" rx="${radius}" fill="url(#b)"/><path d="M16 6.2c4.2 4.6 6.3 7.9 6.3 10.9a6.3 6.3 0 0 1-12.6 0c0-3 2.1-6.3 6.3-10.9Z" fill="#fff"/><path d="M16 22.9v-8M16 17.3l2.5-1.9M16 19.7l-2.5-1.9" stroke="url(#b)" stroke-width="1.5" stroke-linecap="round" fill="none"/></svg>`;
}

export const brandDataUri = (size = 512, radius = 9) => `data:image/svg+xml;base64,${Buffer.from(brandSvg(size, radius)).toString("base64")}`;
