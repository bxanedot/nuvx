import { useCallback, useState } from 'react';
import { Menu, MenuHeader, MenuItem, MenuSeparator } from '@/components/Menu';
import { qualityLabel, setVideoQuality, useVideo } from '@/state/video';

export function VideoQuality({ className = '' }: { className?: string }) {
  const heights = useVideo((s) => s.heights);
  const quality = useVideo((s) => s.quality);
  const activeHeight = useVideo((s) => s.activeHeight);
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  const close = useCallback(() => setAnchor(null), []);

  if (heights.length < 2) return null;
  const selected = quality === 'auto' || !heights.includes(quality) ? activeHeight : quality;
  const label = selected ? qualityLabel(selected) : 'Auto';

  return (
    <>
      <button
        className={`grid h-7 min-w-11 place-items-center overflow-hidden rounded-[8px] bg-white/10 px-2 [backdrop-filter:saturate(1.8)_blur(18px)] [interpolate-size:allow-keywords] [transition:width_0.3s_ease,background-color_0.1s_ease-in,opacity_0.3s_ease] hover:bg-white/20 ${className}`}
        aria-haspopup="menu"
        aria-label={`Video quality: ${quality === 'auto' ? `Auto (${label})` : label}`}
        onClick={(event) => setAnchor(event.currentTarget)}
      >
        <span
          key={label}
          className="animate-morph type-[700_11px/14px] tracking-[0.04em] text-white/70 tabular-nums mix-blend-plus-lighter motion-reduce:animate-none"
        >
          {label}
        </span>
      </button>
      {anchor && (
        <Menu anchor={anchor} placement="below-end" onClose={close} label="Video quality">
          <MenuItem
            checked={quality === 'auto'}
            trailing={quality === 'auto' && selected ? qualityLabel(selected) : undefined}
            onSelect={() => (close(), setVideoQuality('auto'))}
          >
            Auto
          </MenuItem>
          <MenuSeparator />
          <MenuHeader>Quality</MenuHeader>
          {heights.map((height) => (
            <MenuItem
              key={height}
              checked={quality !== 'auto' && selected === height}
              trailing={`${height}p`}
              onSelect={() => (close(), setVideoQuality(height))}
            >
              {qualityLabel(height)}
            </MenuItem>
          ))}
        </Menu>
      )}
    </>
  );
}
