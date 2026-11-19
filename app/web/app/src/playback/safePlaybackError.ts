/**
 * Errors from stream resolvers may contain signed URLs, cookies or authorization headers.
 * Never show their messages in the player UI. Playback can be retried with the Play control.
 */
export function safePlaybackError(error: unknown): string {
  if (error instanceof Error && error.name === 'NotAllowedError') {
    return 'Your browser blocked playback. Press Play to try again.';
  }
  return 'Playback failed. Press Play to try again, or skip this track.';
}
