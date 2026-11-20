export interface MessageSenderIdentity {
  origin?: string | null;
  url?: string | null;
  id?: string | null;
}

function parsedOrigin(url: string): string | null {
  try {
    const parsed = new URL(url);
    if (parsed.origin !== 'null') return parsed.origin;
    // Some runtimes treat extension schemes as opaque even though browsers
    // assign extension pages their own origin. Only these two scheme/host
    // combinations may be reconstructed; data/file/javascript remain denied.
    if (
      (parsed.protocol === 'chrome-extension:' || parsed.protocol === 'moz-extension:') &&
      parsed.hostname && !parsed.username && !parsed.password && !parsed.port
    ) {
      return `${parsed.protocol}//${parsed.hostname}`;
    }
    return null;
  } catch {
    return null;
  }
}

/** Reject inconsistent sender.origin and sender.url identities.
 * Neither field is an instruction from the message payload.
 */
export function authorizedSenderOrigin(
  sender: MessageSenderIdentity,
  allowedOrigins: ReadonlySet<string>,
  ownOrigin: string,
  ownExtensionId: string,
): string | null {
  const explicit = sender.origin ? parsedOrigin(sender.origin) : null;
  const fromUrl = sender.url ? parsedOrigin(sender.url) : null;
  if (sender.origin && !explicit) return null;
  if (sender.url && !fromUrl) return null;
  if (explicit && fromUrl && explicit !== fromUrl) return null;
  const origin = explicit ?? fromUrl;
  if (!origin) return null;
  if (origin === ownOrigin) return sender.id === ownExtensionId ? origin : null;
  // External callers are permitted only from configured site origins; an
  // arbitrary other extension cannot impersonate the internal origin.
  return allowedOrigins.has(origin) ? origin : null;
}
