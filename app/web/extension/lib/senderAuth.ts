export interface MessageSenderIdentity {
  origin?: string | null;
  url?: string | null;
  id?: string | null;
}

function parsedOrigin(url: string): string | null {
  try {
    const origin = new URL(url).origin;
    return origin === 'null' ? null : origin;
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
