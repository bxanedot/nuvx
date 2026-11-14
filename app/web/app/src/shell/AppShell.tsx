import { lazy, Suspense, useEffect, type MouseEvent } from 'react';
import bxaneSignature from '@/assets/bxane-signature.svg';
import chromeWebStoreBadge from '@/assets/chrome-web-store-badge.png';
import { resumeSession } from '@/party/listenTogether';
import { startPartySync } from '@/party/partySync';
import { useDiscordPresence } from '@/playback/discordPresence';
import { startHistoryReporting } from '@/playback/historyReporting';
import { startLivePresence } from '@/playback/livePresence';
import { Outlet } from 'react-router';
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
  const hasTrack = usePlayer((s) => s.nowPlaying !== null);

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
        title="Play YouTube Music with the nuvx extension."
        detail="Playback, YouTube Music sign-in, Spotify and Discord need the extension. Addons and lyrics work without it."
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
        className="sr-only focus:not-sr-only fixed top-2 left-2 z-[calc(var(--z-web-chrome)+1)] rounded-pill bg-key-bg px-4 py-3 text-white focus-visible:ring-2 focus-visible:ring-white"
      >
        Skip to main content
      </a>
      <div className="relative min-h-0 overflow-hidden">
        <Sidebar />
        <MobileTopBar />
        <span
          role="img"
          aria-label="bxane"
          className="pointer-events-none absolute top-4 right-5 z-[calc(var(--z-web-chrome)-2)] h-5 w-[76px] bg-key mask-contain mask-center mask-no-repeat max-sm:right-3.5"
          style={{ maskImage: `url("${bxaneSignature}")` }}
        />
        <main
          id="scrollable-page"
          tabIndex={-1}
          className="absolute inset-0 isolate overflow-x-hidden overflow-y-auto pl-(--web-navigation-width) [scrollbar-gutter:stable] data-tint:bg-(--page-tint) data-tint:transition-[background-color] data-tint:duration-300 data-tint:ease-in max-sm:pt-(--mobile-top-bar-height) max-sm:pl-0"
        >
          <div className="nuvx-ambient" data-motion-paused={backgroundMotion ? undefined : ''} aria-hidden="true" />
          <div
            className={`min-h-full pb-[calc(var(--player-pill-height)+var(--player-bottom-offset)+40px)] ${
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
