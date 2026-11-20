/** Accept canvas media only from a bounded, transport-secure URL.
 * Invalid or unsupported responses leave the static artwork visible.
 */
export function safeCanvasUrl(raw: string | null | undefined): string | null {
  if (!raw || raw.length > 4096) return null;
  try {
    const url = new URL(raw);
    if (url.protocol !== 'https:' || url.username || url.password || (url.port && url.port !== '443')) return null;
    return url.href;
  } catch {
    return null;
  }
}
