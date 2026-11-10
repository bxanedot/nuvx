import type { CSSProperties } from 'react';

interface BlockProps {
  width?: CSSProperties['width'];
  height?: CSSProperties['height'];
  radius?: CSSProperties['borderRadius'];
  aspectRatio?: CSSProperties['aspectRatio'];
  className?: string;
  style?: CSSProperties;
}

const block =
  'relative shrink-0 overflow-hidden rounded-[4px] bg-skeleton after:absolute after:inset-0 after:transform-[translateX(-100%)] after:animate-shimmer after:bg-[linear-gradient(90deg,transparent_0,var(--skeleton-shimmer)_50%,transparent_100%)] motion-reduce:after:hidden motion-reduce:after:animate-none';

export function SkeletonBlock({ width, height, radius, aspectRatio, className, style }: BlockProps) {
  return (
    <div
      aria-hidden="true"
      className={className ? `${block} ${className}` : block}
      style={{ width, height, borderRadius: radius, aspectRatio, ...style }}
    />
  );
}

interface LineProps {
  lineHeight: number;
  fontSize: number;
  width: CSSProperties['width'];
  style?: CSSProperties;
}

export function SkeletonLine({ lineHeight, fontSize, width, style }: LineProps) {
  return (
    <div aria-hidden="true" className="flex items-center" style={{ height: lineHeight, ...style }}>
      <SkeletonBlock width={width} height={Math.round(fontSize * 0.75)} radius={3} />
    </div>
  );
}
