import { useLayoutEffect, useRef } from 'react';
import { claimVideoSurface, type VideoFit } from '@/state/video';

export function VideoSurface({ fit = 'cover', className }: { fit?: VideoFit; className?: string }) {
  const host = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => claimVideoSurface(host.current!, fit), [fit]);
  return <div ref={host} className={className} aria-hidden="true" />;
}
