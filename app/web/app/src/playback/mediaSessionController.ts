import { artworkAtPixels } from '@/lib/artwork';

interface SessionTrack {
  title: string;
  artist: string;
  artworkUrl: string | null;
}

interface SessionPlayer {
  togglePlay(): void;
  seek(seconds: number): void;
  previous(): void;
  next(): void;
}

export class PlayerMediaSessionController {
  private handlersBound = false;

  constructor(
    private readonly getPlayer: () => SessionPlayer,
    private readonly getAudio: () => HTMLAudioElement | null,
    private readonly sounding: () => boolean,
  ) {}

  update(track: SessionTrack | null) {
    if (!('mediaSession' in navigator)) return;
    const session = navigator.mediaSession;
    if (!track) {
      session.metadata = null;
      return;
    }
    session.metadata = new MediaMetadata({
      title: track.title,
      artist: track.artist,
      artwork: track.artworkUrl
        ? [96, 256, 512].map((px) => ({ src: artworkAtPixels(track.artworkUrl!, px), sizes: `${px}x${px}` }))
        : [],
    });

    if (this.handlersBound) return;
    this.handlersBound = true;
    const handle = (action: MediaSessionAction, handler: MediaSessionActionHandler | null) => {
      try {
        session.setActionHandler(action, handler);
      } catch {
      }
    };
    const player = () => this.getPlayer();
    handle('play', () => !this.sounding() && player().togglePlay());
    handle('pause', () => this.sounding() && player().togglePlay());
    handle('seekto', (details) => {
      if (details.seekTime !== undefined) player().seek(details.seekTime);
    });
    handle('seekbackward', (details) => player().seek((this.getAudio()?.currentTime ?? 0) - (details.seekOffset ?? 10)));
    handle('seekforward', (details) => player().seek((this.getAudio()?.currentTime ?? 0) + (details.seekOffset ?? 10)));
    handle('previoustrack', () => player().previous());
    handle('nexttrack', () => player().next());
  }

  updatePosition() {
    const audio = this.getAudio();
    if (!audio || !('mediaSession' in navigator) || !Number.isFinite(audio.duration) || audio.duration <= 0) return;
    try {
      navigator.mediaSession.setPositionState({
        duration: audio.duration,
        playbackRate: audio.playbackRate,
        position: Math.min(audio.currentTime, audio.duration),
      });
    } catch {
    }
  }

  setPlaybackState(state: MediaSessionPlaybackState) {
    if ('mediaSession' in navigator) navigator.mediaSession.playbackState = state;
  }
}
