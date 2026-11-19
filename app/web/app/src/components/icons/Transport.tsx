import type { HTMLAttributes } from 'react';
import { SfSymbolIcon, type SfSymbolName } from './SfSymbol';

type IconProps = Omit<HTMLAttributes<HTMLSpanElement>, 'children'> & { size?: number };

function symbol(name: SfSymbolName) {
  return function PlayerIcon(props: IconProps) {
    return <SfSymbolIcon name={name} {...props} />;
  };
}

export const PlayIcon = symbol('play.fill');
export const PauseIcon = symbol('pause.fill');
export const ForwardIcon = symbol('forward.fill');
export const BackwardIcon = symbol('backward.fill');
export const ShuffleIcon = symbol('shuffle');
export const RepeatIcon = symbol('repeat');
export const RepeatOneIcon = symbol('repeat.1');
export const UpNextIcon = symbol('list.bullet');
export const LyricsIcon = symbol('quote.bubble');
export const LyricsActiveIcon = symbol('quote.bubble.fill');
export const StarIcon = symbol('star');
export const StarFilledIcon = symbol('star.fill');

/** State-change-only play/pause animation, disabled under prefers-reduced-motion. */
export function PlayPauseIcon({ playing, size = 24 }: { playing: boolean; size?: number }) {
  return (
    <span className="play-pause" data-playing={playing} style={{ width: size, height: size }}>
      <PlayIcon size={size} />
      <PauseIcon size={size} />
    </span>
  );
}

/** Match the desktop's SFStudio speaker levels without non-SF SVG paths. */
export function VolumeIcon({ level, ...props }: IconProps & { level: number }) {
  const name: SfSymbolName = level <= 0 ? 'speaker.slash.fill'
    : level < 1 / 3 ? 'speaker.wave.1.fill'
    : level < 2 / 3 ? 'speaker.wave.2.fill'
    : 'speaker.wave.3.fill';
  return <SfSymbolIcon name={name} {...props} />;
}
