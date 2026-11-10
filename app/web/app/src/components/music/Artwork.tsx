import { useState, type CSSProperties } from 'react';
import { sizedArtwork } from '@/lib/artwork';

interface ArtworkProps {
  src: string | null;
  displayWidth: number;
  aspect?: number;
  radius?: CSSProperties['borderRadius'];
  className?: string;
  eager?: boolean;
}

const frame = 'relative isolate w-full overflow-hidden bg-joe artwork-stroke';

export function Artwork({ src, displayWidth, aspect = 1, radius, className, eager }: ArtworkProps) {
  const [loaded, setLoaded] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const sized = src ? sizedArtwork(src, displayWidth, aspect) : null;
  const url = attempt >= 2 || sized === src ? src : sized;
  return (
    <div
      className={className ? `${frame} ${className}` : frame}
      style={{ aspectRatio: String(aspect), borderRadius: radius }}
    >
      {url && (
        <img
          key={`${url}#${attempt}`}
          className="absolute inset-0 size-full object-cover opacity-0 transition-opacity duration-250 ease-in data-loaded:opacity-100"
          data-loaded={loaded || undefined}
          src={url}
          alt=""
          referrerPolicy="no-referrer"
          loading={eager ? 'eager' : 'lazy'}
          decoding="async"
          draggable={false}
          onLoad={() => setLoaded(true)}
          onError={() => {
            if (attempt === 0) setTimeout(() => setAttempt(1), 1500);
            else if (attempt === 1 && url !== src) setAttempt(2);
          }}
        />
      )}
    </div>
  );
}
