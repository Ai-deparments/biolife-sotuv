import { ImageResponse } from "next/og";
import { brandDataUri } from "@/design/brandSvg";

/** PNG of the BioLife mark for the home-screen / Dock icon. Square corners: the OS rounds them itself. */
export function brandPng(size: number) {
  return new ImageResponse(
    (
      // eslint-disable-next-line @next/next/no-img-element
      <img src={brandDataUri(size, 0)} width={size} height={size} alt="" />
    ),
    { width: size, height: size, headers: { "Cache-Control": "public, max-age=86400" } },
  );
}
