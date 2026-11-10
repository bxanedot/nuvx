import { useQuery } from '@tanstack/react-query';
import { create } from 'zustand';
import { usePlayer } from '@/state/player';
import { queryClient } from '@/lib/queryClient';
import { onGranted } from './http';
import { forgetLyrics, lookupLyrics, type LyricsResult, type SourceState } from './repository';
import { useLyricsSettings } from './settings';
import type { LyricsSourceId } from './sources';

interface LyricsUi {
  picked: Record<string, LyricsSourceId>;
  states: Record<string, Partial<Record<LyricsSourceId, SourceState>>>;
  pick: (videoId: string, source: LyricsSourceId | null) => void;
}

export const useLyricsUi = create<LyricsUi>()((set) => ({
  picked: {},
  states: {},
  pick: (videoId, source) =>
    set((s) => {
      const picked = { ...s.picked };
      if (source) picked[videoId] = source;
      else delete picked[videoId];
      return { picked };
    }),
}));

onGranted(() => {
  forgetLyrics();
  void queryClient.invalidateQueries({ queryKey: ['lyrics'] });
});

function reportState(videoId: string, source: LyricsSourceId, state: SourceState) {
  useLyricsUi.setState((s) => ({ states: { ...s.states, [videoId]: { ...s.states[videoId], [source]: state } } }));
}

export function useLyrics() {
  const videoId = usePlayer((s) => s.nowPlaying?.videoId ?? null);
  const title = usePlayer((s) => s.nowPlaying?.title ?? '');
  const artist = usePlayer((s) => s.nowPlaying?.artist ?? '');
  const only = useLyricsUi((s) => (videoId ? s.picked[videoId] : undefined));
  const order = useLyricsSettings((s) => s.order.filter((id) => !s.disabled.includes(id)).join(','));
  const preferSyllables = useLyricsSettings((s) => s.preferSyllables);

  return useQuery<LyricsResult | null>({
    queryKey: ['lyrics', videoId, title, artist, only ?? null, order, preferSyllables],
    enabled: !!videoId && !!title,
    staleTime: Infinity,
    gcTime: 30 * 60_000,
    retry: false,
    queryFn: () => {
      const player = usePlayer.getState();
      const entry = player.queue[player.index];
      const durationSec = entry?.track.durationSec || player.nowPlaying?.durationSec || 0;
      return lookupLyrics(
        {
          videoId: videoId!,
          title,
          artist,
          durationMs: Math.round(durationSec * 1000),
          album: entry?.track.album?.name ?? null,
        },
        { ...(only && { only }), onSource: (source, state) => reportState(videoId!, source, state) },
      );
    },
  });
}
