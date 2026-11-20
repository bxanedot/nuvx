/** Validated Discord community snapshot accepted by the protected publisher route. */
export type CommunityListener = { displayName: string; minutes: number };
export type CommunitySnapshot = {
  memberCount: number;
  activeListeners: number;
  listeners: CommunityListener[];
  updatedAt: number;
};

export function communitySnapshot(value: unknown, now: number = Date.now()): CommunitySnapshot | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const body = value as Record<string, unknown>;
  const { memberCount, activeListeners } = body;
  // Do not coerce null, numeric strings, booleans, or NaN into accepted counts.
  if (typeof memberCount !== "number" || !Number.isInteger(memberCount) || memberCount < 0 || memberCount > 1_000_000) return null;
  if (typeof activeListeners !== "number" || !Number.isInteger(activeListeners) || activeListeners < 0 || activeListeners > 100_000) return null;
  if (!Array.isArray(body.listeners) || body.listeners.length > 10) return null;
  const listeners: CommunityListener[] = [];
  for (const item of body.listeners) {
    if (!item || typeof item !== "object" || Array.isArray(item)) return null;
    const row = item as Record<string, unknown>;
    if (typeof row.displayName !== "string" || typeof row.minutes !== "number") return null;
    const displayName = row.displayName.trim();
    if (!displayName || displayName.length > 80 || !Number.isFinite(row.minutes) || row.minutes <= 0) return null;
    listeners.push({ displayName, minutes: Math.min(10_000_000, Math.floor(row.minutes)) });
  }
  listeners.sort((a, b) => b.minutes - a.minutes);
  return { memberCount, activeListeners, listeners, updatedAt: now };
}
