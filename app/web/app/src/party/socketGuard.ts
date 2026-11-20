/** A reconnect may reuse the same loop ID with a fresh WebSocket.
 * Messages from an older connection are never authoritative after replacement.
 */
export function isCurrentPartySocket(
  expectedLoopId: number,
  activeLoopId: number,
  candidate: object,
  current: object | null,
): boolean {
  return expectedLoopId === activeLoopId && candidate === current;
}
