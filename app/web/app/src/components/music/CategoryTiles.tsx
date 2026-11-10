import { useEffect, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { PrefetchLink } from '@/components/PrefetchLink';
import { firstBrowseArtwork } from '@/data/music/mappers';
import { browseQuery, useYouTubeReady } from '@/data/music/queries';
import { sizedArtwork } from '@/lib/artwork';
import type { Category, CategoryGroup } from '@/data/music/types';
import { browseHref } from './links';

function useNearViewport<T extends HTMLElement>(rootMargin = '600px') {
  const ref = useRef<T | null>(null);
  const [near, setNear] = useState(false);
  useEffect(() => {
    const el = ref.current;
    if (!el || near) return;
    if (!('IntersectionObserver' in window)) {
      setNear(true);
      return;
    }
    const io = new IntersectionObserver(
      (entries) => {
        if (entries.some((e) => e.isIntersecting)) {
          setNear(true);
          io.disconnect();
        }
      },
      { rootMargin },
    );
    io.observe(el);
    return () => io.disconnect();
  }, [near, rootMargin]);
  return { ref, near };
}

const tile =
  'relative flex aspect-video items-end overflow-hidden rounded-artwork-lg bg-[radial-gradient(120%_90%_at_100%_0%,color-mix(in_srgb,var(--tile-color)_70%,#fff)_0%,transparent_70%),linear-gradient(160deg,var(--tile-color),color-mix(in_srgb,var(--tile-color)_55%,#000))] px-3.5 py-3 text-white transition-[scale] duration-200 ease-panel hover:scale-[1.015]';

function CategoryTile({ category }: { category: Category }) {
  const { ready } = useYouTubeReady();
  const { ref, near } = useNearViewport<HTMLLIElement>();
  const [loaded, setLoaded] = useState(false);
  const browse = useQuery({ ...browseQuery(category.link), enabled: ready && near });
  const borrowed = browse.data ? firstBrowseArtwork(browse.data) : null;
  const artwork = borrowed ? (borrowed.wide ? borrowed.thumbnail : sizedArtwork(borrowed.thumbnail, 480)) : null;
  const fade = {
    opacity: loaded ? 1 : 0,
    transition: 'opacity 300ms ease',
  } as React.CSSProperties;
  return (
    <li ref={ref}>
      <PrefetchLink to={browseHref(category.link)} className={tile} style={{ '--tile-color': category.color } as React.CSSProperties}>
        {artwork && (
          <>
            <img
              src={artwork}
              alt=""
              referrerPolicy="no-referrer"
              loading="lazy"
              decoding="async"
              draggable={false}
              onLoad={() => setLoaded(true)}
              className="absolute inset-0 size-full object-cover grayscale contrast-[1.05]"
              style={{ ...fade, objectPosition: '50% 25%' }}
            />
            <span
              aria-hidden="true"
              className="absolute inset-0 mix-blend-multiply"
              style={{ ...fade, background: 'color-mix(in srgb, var(--tile-color) 68%, #fff)' }}
            />
            <span
              aria-hidden="true"
              className="absolute inset-0 mix-blend-screen"
              style={{ ...fade, background: 'color-mix(in srgb, var(--tile-color) 32%, #000)' }}
            />
            <span
              aria-hidden="true"
              className="absolute inset-x-0 bottom-0 h-3/5 bg-[linear-gradient(to_top,rgba(0,0,0,0.45),transparent)]"
              style={fade}
            />
          </>
        )}
        <span aria-hidden="true" className="absolute inset-y-0 left-0 w-2.5" style={{ background: 'var(--tile-color)' }} />
        <span className="relative type-title-2-emphasized [text-shadow:0_1px_8px_#0000004d]">{category.title}</span>
      </PrefetchLink>
    </li>
  );
}

export function CategoryTiles({ groups }: { groups: CategoryGroup[] }) {
  return (
    <>
      {groups.map((group) => (
        <section key={group.title} className="pt-3" aria-label={group.title}>
          <h2 className="mx-(--body-gutter) type-title-2-emphasized">{group.title}</h2>
          <ul className="mx-(--body-gutter) mt-3 mb-8 grid grid-cols-4 gap-5 max-lg:grid-cols-3 max-[607px]:grid-cols-2 max-[607px]:gap-2.5">
            {group.categories.map((category) => (
              <CategoryTile key={`${category.title}-${category.link.params}`} category={category} />
            ))}
          </ul>
        </section>
      ))}
    </>
  );
}
