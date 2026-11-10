import { useEffect } from 'react';
import type { DiscordNowPlaying } from '@nuvx/shared/bridge';
import { getBridge } from '@/extension/bridge';
import { useDiscord, useDiscordOptions } from '@/extension/useDiscord';
import { sizedArtwork } from '@/lib/artwork';
import { getAudio, usePlayer } from '@/state/player';

const ART_PX = 480;

function nowPlaying(): DiscordNowPlaying | null {
  const { nowPlaying: np, queue, index } = usePlayer.getState();
  const audio = getAudio();
  if (!np) return null;
  const durationSec = audio && Number.isFinite(audio.duration) && audio.duration > 0 ? audio.duration : np.durationSec;
  return {
    videoId: np.videoId,
    title: np.title,
    artist: np.artist,
    album: queue[index]?.track.album?.name ?? null,
    artworkUrl: np.artworkUrl ? sizedArtwork(np.artworkUrl, ART_PX) : null,
    positionMs: Math.round((audio?.currentTime ?? 0) * 1000),
    durationMs: Math.round(durationSec * 1000),
    playbackRate: audio?.playbackRate || 1,
  };
}

export function startPublisher(): () => void {
  let sent: string | null = null;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let forced = false;

  const publish = () => {
    const { status } = usePlayer.getState();
    const options = useDiscordOptions.getState().options;
    const bridge = getBridge();
    if (!bridge) return;
    if (status === 'paused' || status === 'idle' || status === 'error') {
      if (sent === 'clear') return;
      sent = 'clear';
      void bridge.call('discord.presence', { track: null, options }).catch(() => (sent = null));
      return;
    }
    if (status !== 'playing') return;
    const track = nowPlaying();
    if (!track) return;
    const key = `${track.videoId}|${JSON.stringify(options)}`;
    if (!forced && sent === key) return;
    forced = false;
    sent = key;
    void bridge.call('discord.presence', { track, options }, { timeoutMs: 30_000 }).catch(() => (sent = null));
  };
  const soon = (force = false) => {
    if (force) forced = true;
    clearTimeout(timer);
    timer = setTimeout(publish, 400);
  };

  let audio: HTMLMediaElement | null = null;
  const onSeeked = () => soon(true);
  const bindAudio = () => {
    audio?.removeEventListener('seeked', onSeeked);
    audio = getAudio();
    audio?.addEventListener('seeked', onSeeked);
  };
  bindAudio();

  const offPlayer = usePlayer.subscribe((state, before) => {
    if (getAudio() !== audio) bindAudio();
    if (state.nowPlaying?.videoId !== before.nowPlaying?.videoId || state.status !== before.status) {
      soon(before.status === 'paused' && state.status === 'playing');
    }
  });
  const offOptions = useDiscordOptions.subscribe(() => soon());
  soon(true);

  return () => {
    clearTimeout(timer);
    offPlayer();
    offOptions();
    audio?.removeEventListener('seeked', onSeeked);
  };
}

export function useDiscordPresence(): void {
  const status = useDiscord();
  const active = status.state === 'ready' && status.discord.available && status.discord.connected && status.discord.enabled;
  useEffect(() => (active ? startPublisher() : undefined), [active]);
}
