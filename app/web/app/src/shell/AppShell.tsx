import { lazy, Suspense, useEffect, type MouseEvent } from 'react';
import bxaneSignature from '@/assets/bxane-signature.svg';
import { resumeSession } from '@/party/listenTogether';
import { startPartySync } from '@/party/partySync';
import { useDiscordPresence } from '@/playback/discordPresence';
import { startHistoryReporting } from '@/playback/historyReporting';
import { startLivePresence } from '@/playback/livePresence';
import { Outlet } from 'react-router';
import { PrefetchLink } from '@/components/PrefetchLink';
import { config } from '@/config';
import { useExtension } from '@/extension/useExtension';
import { isMobileBrowser } from '@/lib/platform';
import { useUi } from '@/state/ui';
import { useKeyboardShortcuts } from './useKeyboardShortcuts';
import { Banner, bannerButtonClass } from './Banner';
import { MobileTopBar } from './MobileTopBar';
import { PlayerBar } from './PlayerBar';
import { SidePanel } from './SidePanel';
import { Sidebar } from './Sidebar';

const mobile = isMobileBrowser();

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
  const bannerDismissed = useUi((s) => s.installBannerDismissed);
  const dismissBanner = useUi((s) => s.dismissInstallBanner);
  const fullScreen = useUi((s) => s.fullScreen);
  const backgroundMotion = useUi((s) => s.backgroundMotion);

  let banner = null;
  if (mobile) {
    banner = (
      <Banner
        title="nuvx is better in the app."
        detail="Download nuvx for Android, Windows, macOS, or Linux."
        action={
          <a className={bannerButtonClass} href={config.appDownloadUrl} target="_blank" rel="noreferrer">
            Get the app
          </a>
        }
      />
    );
  } else if (extension.state === 'missing' && !bannerDismissed) {
    banner = (
      <Banner
        title="Play YouTube Music with the nuvx extension."
        detail="Playback, YouTube Music sign-in, Spotify and Discord need the extension. Addons and lyrics work without it."
        action={
          <PrefetchLink className={bannerButtonClass} to="/extension">
            Get the Extension
          </PrefetchLink>
        }
        onDismiss={dismissBanner}
      />
    );
  }

  return (
    <div className="grid h-full grid-rows-[minmax(0,1fr)_auto]" onClickCapture={bounceGlyph}>
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
          className="absolute inset-0 isolate overflow-x-hidden overflow-y-auto pl-(--web-navigation-width) [scrollbar-gutter:stable] data-tint:bg-(--page-tint) data-tint:transition-[background-color] data-tint:duration-300 data-tint:ease-in max-sm:pt-(--mobile-top-bar-height) max-sm:pl-0"
        >
          <div className="nuvx-ambient" data-motion-paused={backgroundMotion ? undefined : ''} aria-hidden="true" />
          <div className="min-h-full pb-[calc(var(--player-pill-height)+var(--player-bottom-offset)+40px)]">
            <Outlet />
          </div>
        </main>
        <PlayerBar />
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
