import { useEffect, useRef, useState, type CSSProperties, type HTMLAttributes } from 'react';

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

/** Smaller artwork; preserve icon boxes and button hit targets. */
export const SF_SYMBOL_VISUAL_SCALE = 0.8;

export type SfSymbolName = keyof typeof SF_SYMBOL_ASSETS;
export type SfSymbolEffect = 'bounce' | 'pulse' | 'variableColor' | 'wiggle';
export type SfSymbolProps = Omit<HTMLAttributes<HTMLSpanElement>, 'children'> & {
  name: SfSymbolName;
  size?: number;
  /** Effects match SFStudio's layer groups; state-change effects replay on trigger changes. */
  effect?: SfSymbolEffect;
  trigger?: string | number | boolean;
  /** For continuous pulse/variable color, stop the animation when false. */
  active?: boolean;
};

type SymbolLayer = { path: string; group: number; opacity: number };
type SymbolGeometry = { viewBox: string; layers: SymbolLayer[]; variable: number[] };
const geometryCache = new Map<SfSymbolName, Promise<SymbolGeometry | null>>();

function loadGeometry(name: SfSymbolName): Promise<SymbolGeometry | null> {
  const cached = geometryCache.get(name);
  if (cached) return cached;
  const task = (async () => {
    const url = `/sf-symbols/${SF_SYMBOL_ASSETS[name]}.svg`;
    const response = await fetch(url);
    if (!response.ok) return null;
    const document = new DOMParser().parseFromString(await response.text(), 'image/svg+xml');
    const svg = document.documentElement;
    if (svg.localName !== 'svg' || svg.getAttribute('data-symbol') !== name) return null;
    const viewBox = svg.getAttribute('viewBox');
    if (!viewBox || svg.querySelector('parsererror')) return null;
    const paths = Array.from(svg.querySelectorAll('path'));
    // Knock-out paths require compositing. Keep those exact shapes as SVG masks instead
    // of drawing their erase paths in currentColor.
    if (paths.length === 0 || paths.some((path) => path.hasAttribute('data-erase'))) return null;
    const variable = (svg.getAttribute('data-variable') ?? '')
      .split(/\s+/).filter(Boolean).map(Number).filter(Number.isFinite);
    return {
      viewBox,
      variable,
      layers: paths.map((path) => ({
        path: path.getAttribute('d') ?? '',
        group: Number(path.getAttribute('data-group') ?? 0),
        opacity: Number(path.getAttribute('fill-opacity') ?? 1),
      })),
    };
  })().catch(() => null);
  geometryCache.set(name, task);
  return task;
}

/**
 * SFStudio-exported symbol with the web's native SVG layer animations.
 * Static icons retain the fast CSS-mask path. Animated icons load the local SVG's
 * data-group / data-variable metadata and animate individual layers.
 * Browsers do not run SFStudio's Kotlin/Compose animation engine.
 */
export function SfSymbolIcon({
  name, size = 24, className, style, effect, trigger, active = true, ...props
}: SfSymbolProps) {
  const [loaded, setLoaded] = useState<{ name: SfSymbolName; geometry: SymbolGeometry | null } | null>(null);
  const [cycle, setCycle] = useState(0);
  const previousTrigger = useRef(trigger);

  useEffect(() => {
    if (!effect) return;
    let mounted = true;
    void loadGeometry(name).then((geometry) => {
      if (mounted) setLoaded({ name, geometry });
    });
    return () => { mounted = false; };
  }, [name, effect]);

  useEffect(() => {
    if (trigger !== undefined && !Object.is(previousTrigger.current, trigger)) {
      previousTrigger.current = trigger;
      setCycle((value) => value + 1);
    }
  }, [trigger]);

  const url = `/sf-symbols/${SF_SYMBOL_ASSETS[name]}.svg`;
  const visualSize = `${SF_SYMBOL_VISUAL_SCALE * 100}%`;
  const geometry = loaded?.name === name ? loaded.geometry : null;
  const isContinuous = effect === 'pulse' || effect === 'variableColor';
  const playing = Boolean(effect && active && (isContinuous || cycle > 0));
  const base: CSSProperties = {
    display: 'inline-grid',
    placeItems: 'center',
    width: size,
    height: size,
    flexShrink: 0,
    verticalAlign: 'middle',
    ...style,
  };
  const fallback: CSSProperties = {
    ...base,
    backgroundColor: 'currentColor',
    maskImage: `url("${url}")`,
    WebkitMaskImage: `url("${url}")`,
    maskRepeat: 'no-repeat',
    WebkitMaskRepeat: 'no-repeat',
    maskSize: `${visualSize} ${visualSize}`,
    WebkitMaskSize: `${visualSize} ${visualSize}`,
    maskPosition: 'center',
    WebkitMaskPosition: 'center',
  };

  return (
    <span
      key={`${name}-${cycle}`}
      {...props}
      className={className}
      data-sf-symbol={name}
      data-sf-renderer={geometry ? 'layers' : 'mask'}
      data-sf-effect={playing ? effect : undefined}
      aria-hidden={props['aria-label'] ? undefined : true}
      style={geometry ? base : fallback}
    >
      {geometry && (
        <svg
          key={`${name}-${cycle}`}
          className="sf-symbol-svg"
          data-sf-effect={playing ? effect : undefined}
          width={size}
          height={size}
          viewBox={geometry.viewBox}
          fill="currentColor"
          aria-hidden="true"
          style={{ display: 'block', overflow: 'visible', width: visualSize, height: visualSize }}
        >
          {geometry.layers.map((layer, index) => {
            const variableOrder = geometry.variable.indexOf(layer.group);
            const timing = {
              '--sf-delay': `${layer.group * 80}ms`,
              '--sf-variable-delay': `${Math.max(0, variableOrder) * 333}ms`,
            } as CSSProperties;
            return (
              <path
                key={index}
                className="sf-symbol-layer"
                data-sf-group={layer.group}
                d={layer.path}
                fillOpacity={layer.opacity}
                style={timing}
              />
            );
          })}
        </svg>
      )}
    </span>
  );
}
