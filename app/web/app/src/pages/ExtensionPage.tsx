import { ChevronRight } from 'lucide-react';
import type { ComponentType } from 'react';
import { SiDiscord, SiSpotify, SiYoutubemusic } from 'react-icons/si';
import type { Capability } from '@nuvx/shared/bridge';
import { PageHeader } from '@/components/PageHeader';
import { currentBrowser, downloadsFor } from '@/extension/downloads';
import { useExtension, type ExtensionStatus } from '@/extension/useExtension';
import { useYtAuth, type YtAuthStatus } from '@/extension/useYtAuth';
import { PrefetchLink } from '@/components/PrefetchLink';
import { DiscordIntegration, SpotifyIntegration } from './IntegrationSettings';
import { settingsUi } from './settingsStyles';

interface Integration {
  name: string;
  Icon: ComponentType<{ size?: number; className?: string }>;
  capability: Capability;
  ready: string;
}

const INTEGRATIONS: Integration[] = [
  { name: 'YouTube Music', Icon: SiYoutubemusic, capability: 'yt', ready: 'Playback ready · not signed in' },
  { name: 'Spotify', Icon: SiSpotify, capability: 'spotify', ready: 'Not connected' },
  { name: 'Discord', Icon: SiDiscord, capability: 'discord', ready: 'Off' },
];

function youtubeStatus(auth: YtAuthStatus): string | null {
  if (auth.state !== 'ready') return null;
  const { browserSignedIn, connected, active } = auth.auth;
  if (!browserSignedIn) return 'Playback ready · not signed in';
  if (!connected) return 'Disconnected';
  return active ? `Signed in as ${active.name}` : 'Signed in · no account could be read';
}

function integrationStatus(status: ExtensionStatus, integration: Integration, auth: YtAuthStatus): string {
  if (status.state === 'checking') return 'Checking…';
  if (status.state === 'missing') return 'Needs the extension';
  if (!status.extension.info.capabilities.includes(integration.capability)) return 'Not available in this version yet';
  return (integration.capability === 'yt' && youtubeStatus(auth)) || integration.ready;
}

export default function ExtensionPage() {
  const status = useExtension();
  const downloads = downloadsFor();
  const auth = useYtAuth();
  const orderedDownloads = [...downloads].sort((a, b) => Number(b.browser === currentBrowser) - Number(a.browser === currentBrowser));

  return (
    <div className={settingsUi.centered}>
      <PageHeader title="nuvx Extension" />
      <div className={settingsUi.body}>
        <section className={settingsUi.group} aria-live="polite">
          <div className={settingsUi.row}>
            <span>Status</span>
            <span className={settingsUi.value}>
              {status.state === 'checking' && 'Checking…'}
              {status.state === 'missing' && 'Not installed'}
              {status.state === 'present' && (
                <>
                  <span className={settingsUi.dot} aria-hidden="true" />
                  Installed · v{status.extension.info.version}
                </>
              )}
            </span>
          </div>
          {status.state === 'present' && (
            <div className={settingsUi.row}>
              <span>Connection</span>
              <span className={settingsUi.value}>
                {status.extension.bridge.transport === 'external' ? 'Direct' : 'Page relay'} ·{' '}
                {status.extension.info.browser === 'firefox' ? 'Firefox' : 'Chromium'}
              </span>
            </div>
          )}
        </section>
        <p className={settingsUi.footer}>
          The version above is the extension installed in your browser. The web player can receive features independently.
          Chrome and Firefox install extension updates automatically after store approval.
        </p>


        <h2 className={settingsUi.sectionHeader}>Get the extension</h2>
        <section className={settingsUi.group}>
          {orderedDownloads.map(({ browser, title, detail, href, Icon }) => {
            const content = (
              <>
                <Icon size={28} className="text-primary" />
                <span className={settingsUi.downloadText}>
                  <span className={settingsUi.downloadTitle}>{title}</span>
                  <span className={settingsUi.downloadDetail}>
                    {detail}
                    {browser === currentBrowser && ' · This browser'}
                  </span>
                </span>
                {href && <ChevronRight size={16} strokeWidth={2.4} className={settingsUi.chevron} />}
              </>
            );
            return href ? (
              <a key={browser} className={settingsUi.download} href={href} target="_blank" rel="noreferrer">
                {content}
              </a>
            ) : (
              <div key={browser} className={settingsUi.download} aria-disabled="true">
                {content}
              </div>
            );
          })}
        </section>
        <p className={settingsUi.footer}>
          New to this?{' '}
          <PrefetchLink to="/guide" className={settingsUi.inlineLink}>
            Follow the step-by-step guide
          </PrefetchLink>
          .
        </p>

        <h2 className={settingsUi.sectionHeader}>Integrations</h2>
        <section className={settingsUi.group}>
          {INTEGRATIONS.map((integration) => (
            <div key={integration.name} className={settingsUi.integration}>
              <span className={settingsUi.integrationIcon} aria-hidden="true">
                <integration.Icon size={15} />
              </span>
              <span className={settingsUi.integrationName}>{integration.name}</span>
              {status.state === 'present' && integration.capability === 'spotify' ? (
                <SpotifyIntegration />
              ) : status.state === 'present' && integration.capability === 'discord' ? (
                <DiscordIntegration />
              ) : (
                <span className={settingsUi.value}>{integrationStatus(status, integration, auth)}</span>
              )}
            </div>
          ))}
        </section>
        {auth.state === 'ready' && auth.auth.problem && (
          <p className={settingsUi.footer}>YouTube Music: {auth.auth.problem}.</p>
        )}
        <p className={settingsUi.footer}>
          Sign-ins stay inside the extension: this page only ever sees results, never your cookies or tokens.
        </p>
        <p className={settingsUi.footer}>
          Spotify Canvas, Discord presence and Listen Together are in{' '}
          <PrefetchLink to="/settings" className={settingsUi.inlineLink}>
            Settings
          </PrefetchLink>
          .
        </p>
      </div>
    </div>
  );
}
