import { LogoMark } from '@/components/icons/LogoMark';
import { PauseIcon, PlayIcon } from '@/components/icons/Transport';
import { sizedArtwork } from '@/lib/artwork';
import { usePlayer } from '@/state/player';
import { useUi } from '@/state/ui';

export function MobilePlayerBar() {
  const nowPlaying = usePlayer((s) => s.nowPlaying);
  const playing = usePlayer((s) => s.status === 'playing' || s.status === 'buffering');
  const togglePlay = usePlayer((s) => s.togglePlay);
  const setFullScreen = useUi((s) => s.setFullScreen);

  if (!nowPlaying) return null;

  return (
    <div className="absolute inset-x-3 bottom-3 z-[calc(var(--z-web-chrome)-1)] hidden max-sm:block">
      <div className="glass-pill grid h-(--mini-player-height) grid-cols-[40px_minmax(0,1fr)_44px] items-center gap-2 rounded-[14px] px-2">
        <button
          className="grid size-10 place-items-center overflow-hidden rounded-[8px] bg-quinary"
          aria-label="Open full screen player"
          onClick={() => setFullScreen(true)}
        >
          {nowPlaying.artworkUrl ? (
            <img className="size-full object-cover" src={sizedArtwork(nowPlaying.artworkUrl, 80)} alt="" referrerPolicy="no-referrer" />
          ) : (
            <LogoMark size={22} className="text-secondary" />
          )}
        </button>
        <button className="flex min-w-0 flex-col text-start" onClick={() => setFullScreen(true)}>
          <span className="w-full truncate type-body-emphasized">{nowPlaying.title}</span>
          <span className="w-full truncate type-callout text-secondary">{nowPlaying.artist}</span>
        </button>
        <button
          className="grid size-11 place-items-center rounded-full"
          aria-label={playing ? 'Pause' : 'Play'}
          onClick={togglePlay}
        >
          {playing ? <PauseIcon size={24} /> : <PlayIcon size={24} />}
        </button>
      </div>
    </div>
  );
}
