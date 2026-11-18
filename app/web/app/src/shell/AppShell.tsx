import { lazy, Suspense, useEffect, useRef, type MouseEvent } from 'react';
import chromeWebStoreBadge from '@/assets/chrome-web-store-badge.png';
import { resumeSession } from '@/party/listenTogether';
import { startPartySync } from '@/party/partySync';
import { useDiscordPresence } from '@/playback/discordPresence';
import { startHistoryReporting } from '@/playback/historyReporting';
import { startLivePresence } from '@/playback/livePresence';
import { Outlet, useLocation } from 'react-router';
import { PrefetchLink } from '@/components/PrefetchLink';
import { config } from '@/config';
import { useExtension } from '@/extension/useExtension';
import { currentBrowser, downloadsFor } from '@/extension/downloads';
import { isMobileBrowser } from '@/lib/platform';
import { useUi } from '@/state/ui';
import { usePlayer } from '@/state/player';
import { useKeyboardShortcuts } from './useKeyboardShortcuts';
import { Banner, bannerButtonClass } from './Banner';
import { MobileTopBar } from './MobileTopBar';
import { MobilePlayerBar } from './MobilePlayerBar';
import { PlayerBar } from './PlayerBar';
import { SidePanel } from './SidePanel';
import { Sidebar } from './Sidebar';

const mobile = isMobileBrowser();
const android = /Android/i.test(navigator.userAgent);

const FullScreenPlayer = lazy(() => import('./FullScreenPlayer').then((m) => ({ default: m.FullScreenPlayer })));

const publicPages: Record<string, { title: string; description: string }> = {
  '/guide': {
    title: 'Web Player Guide | nuvx',
    description: 'Install the nuvx browser extension and connect it to the free, open-source YouTube Music web player.',
  },
  '/extension': {
    title: 'nuvx Browser Extension',
    description: 'Connect YouTube Music, Spotify, Discord and Listen Together to the free, open-source nuvx web player.',
  },
  '/downloads': {
    title: 'nuvx Downloads',
    description: 'Download nuvx apps and browser extensions for Android, desktop and Firefox or Chromium browsers.',
  },
};

function bounceGlyph({ target }: MouseEvent<HTMLDivElement>) {
  if (!(target instanceof Element) || matchMedia('(prefers-reduced-motion: reduce)').matches) return;
  const control = target.closest('button:enabled, a[href]');
  if (!control) return;
  requestAnimationFrame(() => {
    control.querySelector(':scope > svg, :scope > span > svg')?.animate([
      { translate: '0 0', easing: 'cubic-bezier(.55, 0, .67, 1)' },
      { translate: '0 -18%', offset: .31, easing: 'cubic-bezier(.45, 0, .67, 1)' },
      { translate: '0 6%', offset: .62, easing: 'cubic-bezier(.25, 0, .55, 1)' },
      { translate: '0 0' },
    ], { duration: 700, id: 'glyph-bounce' });
  });
}

export function AppShell() {
  const location = useLocation();
  const ambientVideo = useRef<HTMLVideoElement>(null);
  useKeyboardShortcuts();
  useDiscordPresence();
  useEffect(() => {
    resumeSession();
    return startPartySync();
  }, []);
  useEffect(() => startHistoryReporting(), []);
  useEffect(() => startLivePresence(), []);
  const extension = useExtension();
  const browserDownload = downloadsFor().find(({ browser, source }) => browser === currentBrowser && source === 'store');
  const BrowserDownloadIcon = browserDownload?.Icon;
  const bannerDismissed = useUi((s) => s.installBannerDismissed);
  const dismissBanner = useUi((s) => s.dismissInstallBanner);
  const fullScreen = useUi((s) => s.fullScreen);
  const backgroundMotion = useUi((s) => s.backgroundMotion);
  const backgroundMonochrome = useUi((s) => s.backgroundMonochrome);
  const hasTrack = usePlayer((s) => s.nowPlaying !== null);

  useEffect(() => {
    const pathname = location.pathname.replace(/\/+$/, '') || '/';
    const meta = publicPages[pathname];
    const title = meta?.title ?? 'nuvx Web Player — YouTube Music in your browser';
    const description = meta?.description ?? 'Play YouTube Music in your browser with nuvx: synced lyrics, Listen Together, Spotify playlists and Discord status. Free and open source.';
    const canonical = `https://web.nuvx.lol${pathname === '/' ? '/' : pathname}`;
    document.title = title;
    document.querySelector('meta[name="description"]')?.setAttribute('content', description);
    document.querySelector('meta[name="robots"]')?.setAttribute('content', meta ? 'index,follow' : 'noindex,follow');
    document.querySelector('meta[name="author"]')?.setAttribute('content', 'Licht');
    document.querySelector('link[rel="canonical"]')?.setAttribute('href', canonical);
    document.querySelector('meta[property="og:url"]')?.setAttribute('content', canonical);
    document.querySelector('meta[property="og:title"]')?.setAttribute('content', title);
    document.querySelector('meta[property="og:description"]')?.setAttribute('content', description);
    document.querySelector('meta[name="twitter:title"]')?.setAttribute('content', title);
    document.querySelector('meta[name="twitter:description"]')?.setAttribute('content', description);
    let structuredData = document.querySelector<HTMLScriptElement>('#nuvx-page-schema');
    if (!structuredData) {
      structuredData = document.createElement('script');
      structuredData.id = 'nuvx-page-schema';
      structuredData.type = 'application/ld+json';
      document.head.append(structuredData);
    }
    structuredData.textContent = JSON.stringify({
      '@context': 'https://schema.org',
      '@type': meta ? 'WebPage' : 'SoftwareApplication',
      name: title,
      description,
      url: canonical,
      author: { '@type': 'Person', name: 'Licht', url: 'https://github.com/lylicht' },
      ...(meta ? {} : { applicationCategory: 'MultimediaApplication', operatingSystem: 'Web browser', offers: { '@type': 'Offer', price: '0', priceCurrency: 'USD' } }),
    });
  }, [location.pathname]);

  useEffect(() => {
    const video = ambientVideo.current;
    const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)');
    const sync = () => {
      if (backgroundMotion && !reducedMotion.matches) video?.play().catch(() => {});
      else video?.pause();
    };
    sync();
    reducedMotion.addEventListener('change', sync);
    return () => reducedMotion.removeEventListener('change', sync);
  }, [backgroundMotion]);

  let banner = null;
  if (mobile) {
    banner = (
      <Banner
        title="nuvx is better in the app."
        detail={android ? 'Download the universal Android APK.' : 'Download nuvx for Android, Windows, macOS, or Linux.'}
        action={android ? (
          <a className={bannerButtonClass} href={config.androidDownloadUrl}>Download for Android</a>
        ) : (
          <PrefetchLink className={bannerButtonClass} to="/downloads">Choose a download</PrefetchLink>
        )}
      />
    );
  } else if (extension.state === 'missing' && !bannerDismissed) {
    banner = (
      <Banner
        title="Nuvx works best with its extension."
        detail="Connect the extension for playback, Spotify and Discord presence. Addons and lyrics work without it."
        action={
          browserDownload?.href && BrowserDownloadIcon ? (
            <a
              className={currentBrowser === 'chrome' ? 'inline-flex rounded-md focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white' : bannerButtonClass}
              href={browserDownload.href}
              target="_blank"
              rel="noreferrer"
            >
              {currentBrowser === 'chrome' ? (
                <img src={chromeWebStoreBadge} alt="Available in the Chrome Web Store" width={206} height={58} />
              ) : (
                <>
                  <BrowserDownloadIcon size={15} aria-hidden="true" />
                  Add to Firefox
                </>
              )}
            </a>
          ) : (
            <PrefetchLink className={bannerButtonClass} to="/extension">
              Get the Extension
            </PrefetchLink>
          )
        }
        onDismiss={dismissBanner}
      />
    );
  }

  return (
    <div className="grid h-full grid-rows-[minmax(0,1fr)_auto]" onClickCapture={bounceGlyph}>
      <a
        href="#scrollable-page"
        className="sr-only focus:not-sr-only fixed top-2 left-2 z-[calc(var(--z-web-chrome)+1)] rounded-pill bg-key-bg px-4 py-3 text-pill focus-visible:ring-2 focus-visible:ring-white"
      >
        Skip to main content
      </a>
      <div className="relative min-h-0 overflow-hidden">
        <Sidebar />
        <MobileTopBar />
        <a
          href="https://guns.lol/lylicht"
          target="_blank"
          rel="noopener noreferrer"
          aria-label="Licht on Guns.lol"
          className="absolute top-4 right-5 z-[calc(var(--z-web-chrome)-2)] block max-sm:right-3.5"
        >
          <img src="/licht-signature.png" width="92" height="30" alt="Licht" className="app-licht-signature" />
        </a>
        <main
          id="scrollable-page"
          tabIndex={-1}
          className="absolute inset-0 isolate overflow-x-hidden overflow-y-auto pl-(--web-navigation-width) [scrollbar-gutter:stable] max-sm:pt-(--mobile-top-bar-height) max-sm:pl-0"
        >
          <div
            className="nuvx-ambient"
            data-motion-paused={backgroundMotion ? undefined : ''}
            data-background-monochrome={backgroundMonochrome ? '' : undefined}
            aria-hidden="true"
          >
            <video ref={ambientVideo} poster="/mesh-gradient-poster.webp" muted loop playsInline preload="none" tabIndex={-1}>
              <source src="/mesh-gradient-web.webm" type="video/webm" />
              <source src="/mesh-gradient-web.mp4" type="video/mp4" />
            </video>
          </div>
          <div
            className={`relative z-[1] min-h-full pb-[calc(var(--player-pill-height)+var(--player-bottom-offset)+40px)] ${
              hasTrack ? 'max-sm:pb-[calc(var(--mini-player-height)+24px)]' : 'max-sm:pb-10'
            }`}
          >
            <Outlet />
          </div>
        </main>
        <PlayerBar />
        <MobilePlayerBar />
        <SidePanel />
      </div>
      {banner}
      {fullScreen && (
        <Suspense fallback={null}>
          <FullScreenPlayer />
        </Suspense>
      )}
    </div>
  );
}
