import type { CSSProperties, HTMLAttributes } from 'react';

/**
 * Web adapter for SFStudio's exported SF Symbols. Assets are local to the web app
 * and copied from the SFStudio-derived vector set already present in Nuvx Desktop.
 * The browser does not run the Kotlin/Compose SFStudio renderer.
 */
export const SF_SYMBOL_ASSETS = {
  "house.fill": "house.fill",
  "magnifyingglass": "search",
  "square.grid.2x2.fill": "explore",
  "clock.arrow.trianglehead.counterclockwise.rotate.90": "history",
  "person": "person",
  "square.stack.fill": "library",
  "music.note": "music_note",
  "video": "video",
  "sparkles": "sparkles",
  "list.bullet": "queue",
  "dot.radiowaves.left.and.right": "radio",
  "microphone": "podcast",
  "play.fill": "play",
  "pause.fill": "pause",
  "backward.fill": "prev",
  "forward.fill": "next",
  "shuffle": "shuffle",
  "repeat": "repeat",
  "repeat.1": "repeat_one",
  "quote.bubble": "lyrics_off",
  "quote.bubble.fill": "lyrics",
  "star": "star",
  "star.fill": "star_filled",
  "speaker.slash.fill": "volume_0",
  "speaker.wave.1.fill": "volume_1",
  "speaker.wave.2.fill": "volume_2",
  "speaker.wave.3.fill": "volume_3",
  "gearshape": "settings",
  "arrow.down.circle": "download",
  "person.crop.circle": "person_circle",
  "pencil.tip": "pencil",
  "wifi": "wifi",
  "keyboard": "keyboard",
  "paintbrush.pointed": "paintbrush",
  "waveform": "waveform",
  "play": "play_outline",
  "arrow.down.document": "import",
  "arrow.up.left.and.arrow.down.right": "maximize2"
} as const;

export type SfSymbolName = keyof typeof SF_SYMBOL_ASSETS;
export type SfSymbolProps = Omit<HTMLAttributes<HTMLSpanElement>, 'children'> & {
  name: SfSymbolName;
  size?: number;
};

/** Monochrome SF Symbol that inherits currentColor and supports existing icon sizing. */
export function SfSymbolIcon({ name, size = 24, className, style, ...props }: SfSymbolProps) {
  const url = `/sf-symbols/${SF_SYMBOL_ASSETS[name]}.svg`;
  const mask: CSSProperties = {
    display: 'inline-block',
    width: size,
    height: size,
    flexShrink: 0,
    verticalAlign: 'middle',
    backgroundColor: 'currentColor',
    maskImage: `url("${url}")`,
    WebkitMaskImage: `url("${url}")`,
    maskRepeat: 'no-repeat',
    WebkitMaskRepeat: 'no-repeat',
    maskSize: 'contain',
    WebkitMaskSize: 'contain',
    maskPosition: 'center',
    WebkitMaskPosition: 'center',
    ...style,
  };
  return (
    <span
      {...props}
      className={className}
      data-sf-symbol={name}
      aria-hidden={props['aria-label'] ? undefined : true}
      style={mask}
    />
  );
}
