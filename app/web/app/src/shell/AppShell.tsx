import { lazy, Suspense, useEffect, type MouseEvent } from 'react';
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

// It carries am-lyrics; loaded the first time it opens, as the side panel's lyrics are.
const FullScreenPlayer = lazy(() => import('./FullScreenPlayer').then((m) => ({ default: m.FullScreenPlayer })));

function bounceGlyph({ target }: MouseEvent<HTMLDivElement>) {
  if (!(target instanceof Element) || matchMedia('(prefers-reduced-motion: reduce)').matches) return;
  const control = target.closest('button:enabled, a[href]');
  if (!control) return;
  // Wait for React to swap stateful icons such as Play and Pause before animating.
  requestAnimationFrame(() => {
    control.querySelector(':scope > svg, :scope > span > svg')?.animate([
      { translate: '0 0', easing: 'cubic-bezier(.55, 0, .67, 1)' },
      { translate: '0 -18%', offset: .31, easing: 'cubic-bezier(.45, 0, .67, 1)' },
      { translate: '0 6%', offset: .62, easing: 'cubic-bezier(.25, 0, .55, 1)' },
      { translate: '0 0' },
    ], { duration: 700, id: 'glyph-bounce' });
  });
}

/**
 * The app frame (report §1): glass sidebar, the scrolling page, the floating
 * player pill, the side panel, and the bottom band when there is one.
 */
export function AppShell() {
  useKeyboardShortcuts();
  useDiscordPresence();
  // Listen Together: pick a party back up after a reload, and keep the player in step with it.
  useEffect(() => {
    resumeSession();
    return startPartySync();
  }, []);
  // Plays into the account's YouTube Music history (Settings › Share Listening Activity).
  useEffect(() => startHistoryReporting(), []);
  // Counts this tab in the "listening now" figure beside the phone and PC apps.
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
      {/* The side panel slides in from beyond the right edge; never let it scroll the page. */}
      <div className="relative min-h-0 overflow-hidden">
        <Sidebar />
        <MobileTopBar />
        <span className="pointer-events-none absolute top-3 right-5 z-[calc(var(--z-web-chrome)-2)] rounded-full px-2 py-0.5 font-serif text-[17px]/5 tracking-tight text-key italic glass-material max-sm:right-3.5">
          bxane
        </span>
        {/* The page scrolls under the fixed sidebar and the floating player. */}
        <main
          id="scrollable-page"
          className="absolute inset-0 isolate overflow-x-hidden overflow-y-auto pl-(--web-navigation-width) [scrollbar-gutter:stable] data-tint:bg-(--page-tint) data-tint:transition-[background-color] data-tint:duration-300 data-tint:ease-in max-sm:pt-(--mobile-top-bar-height) max-sm:pl-0"
        >
          <div className="nuvx-ambient" data-motion-paused={backgroundMotion ? undefined : ''} aria-hidden="true" />
          {/* Clears the floating pill: its height, its offset, and some air. */}
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
