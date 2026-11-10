export const INVITE_ORIGIN = 'https://nuvx.lol';
export const CODE_LENGTH = 6;

export function cleanCode(raw: string): string | null {
  const cleaned = raw.replace(/[^A-Za-z0-9]/g, '').toUpperCase();
  return cleaned.length === CODE_LENGTH ? cleaned : null;
}

export function sanitizeServerUrl(raw: string | null | undefined): string | null {
  const trimmed = raw?.trim().replace(/\/+$/, '');
  if (!trimmed) return null;
  const withScheme = /^https?:\/\//i.test(trimmed) ? trimmed : `https://${trimmed}`;
  try {
    return new URL(withScheme).host ? withScheme : null;
  } catch {
    return null;
  }
}

export function parseInvite(value: string): { code: string; serverUrl: string | null } | null {
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    return null;
  }
  const server = sanitizeServerUrl(url.searchParams.get('server'));
  if (url.protocol === 'nuvx:' && url.host.toLowerCase() === 'party') {
    const code = cleanCode(url.pathname.replace(/^\/+|\/+$/g, '') || url.searchParams.get('code') || '');
    return code ? { code, serverUrl: server } : null;
  }
  const match = url.pathname.match(/^\/invite\/([A-Za-z0-9]{6})$/);
  if ((url.protocol === 'https:' || url.protocol === 'http:') && match) return { code: match[1]!.toUpperCase(), serverUrl: server };
  return null;
}

export function inviteUrl(code: string, customServer: string | null): string {
  const base = customServer?.trim().replace(/\/+$/, '');
  const origin = base && base.toLowerCase() !== INVITE_ORIGIN ? base : INVITE_ORIGIN;
  return `${origin}/invite/${code.toUpperCase()}`;
}
