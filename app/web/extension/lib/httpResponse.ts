import type { HttpResponse } from '@nuvx/shared/bridge';

const MAX_BYTES = 8 * 1024 * 1024;

async function readBody(response: Response): Promise<Uint8Array> {
  const length = Number(response.headers.get('content-length'));
  if (Number.isFinite(length) && length > MAX_BYTES) {
    await response.body?.cancel().catch(() => {});
    throw Object.assign(new Error('The upstream response exceeded the 8 MB limit.'), { code: 'response_too_large' });
  }
  const reader = response.body?.getReader();
  if (!reader) return new Uint8Array();
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      if (size + value.byteLength > MAX_BYTES) {
        await reader.cancel().catch(() => {});
        throw Object.assign(new Error('The upstream response exceeded the 8 MB limit.'), { code: 'response_too_large' });
      }
      size += value.byteLength;
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  return bytes;
}

function toBase64(bytes: Uint8Array): string {
  let binary = '';
  for (let i = 0; i < bytes.length; i += 0x8000) binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(binary);
}

export async function serialiseResponse(response: Response, unknownTypeIsText = false): Promise<HttpResponse> {
  const headers: Record<string, string> = {};
  response.headers.forEach((value, key) => (headers[key] = value));
  const type = response.headers.get('content-type') ?? '';
  const textual = /json|text|javascript|xml|html/.test(type) || (unknownTypeIsText && !type);
  const bytes = await readBody(response);
  return {
    status: response.status,
    headers,
    url: response.url,
    bodyEncoding: textual ? 'text' : 'base64',
    body: textual ? new TextDecoder().decode(bytes) : toBase64(bytes),
  };
}
