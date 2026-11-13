import { ChevronRight } from 'lucide-react';
import { useState } from 'react';
import { PageHeader } from '@/components/PageHeader';
import { PrefetchLink } from '@/components/PrefetchLink';
import { currentBrowser, downloadsFor, type Browser, type InstallSource } from '@/extension/downloads';
import { useExtension } from '@/extension/useExtension';
import { settingsUi } from './settingsStyles';

export default function GuidePage() {
  const status = useExtension();
  const downloads = downloadsFor();
  const [browser, setBrowser] = useState<Browser>(currentBrowser);
  const [source, setSource] = useState<InstallSource>('store');
  const chosen = downloads.find((d) => d.browser === browser && d.source === source)!;

  return (
    <div className={settingsUi.centered}>
      <PageHeader title="Guide" />
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
        </section>
        <p className={settingsUi.footer}>
          nuvx plays YouTube Music through a small browser extension. It does the networking and sign-in a web page isn’t
          allowed to do.
        </p>

        <h2 className={settingsUi.sectionHeader}>Install the extension</h2>
        <div role="group" aria-label="Browser" className="mx-4 mb-3 flex flex-wrap gap-2">
          {downloads.filter((d) => d.source === 'store').map((d) => (
            <button
              key={d.browser}
              type="button"
              aria-pressed={browser === d.browser}
              onClick={() => setBrowser(d.browser)}
              className={settingsUi.guideTab}
            >
              {d.browser === 'firefox' ? 'Firefox' : 'Chrome, Edge, Brave'}
            </button>
          ))}
        </div>
        <div role="group" aria-label="Install source" className="mx-4 mb-3 flex flex-wrap gap-2">
          {(['store', 'github'] as const).map((option) => (
            <button
              key={option}
              type="button"
              aria-pressed={source === option}
              onClick={() => setSource(option)}
              className={settingsUi.guideTab}
            >
              {option === 'store' ? 'Browser store' : 'GitHub ZIP'}
            </button>
          ))}
        </div>
        <section className={settingsUi.group}>
          {chosen.href ? (
            <a className={settingsUi.download} href={chosen.href} target="_blank" rel="noreferrer">
              <chosen.Icon size={28} className="text-primary" />
              <span className={settingsUi.downloadText}>
                <span className={settingsUi.downloadTitle}>{chosen.title}</span>
                <span className={settingsUi.downloadDetail}>{chosen.detail}</span>
              </span>
              <ChevronRight size={16} strokeWidth={2.4} className={settingsUi.chevron} />
            </a>
          ) : (
            <div className={settingsUi.download} aria-disabled="true">
              <chosen.Icon size={28} className="text-primary" />
              <span className={settingsUi.downloadText}>
                <span className={settingsUi.downloadTitle}>{chosen.title}</span>
                <span className={settingsUi.downloadDetail}>{chosen.detail}</span>
              </span>
            </div>
          )}
        </section>
        <p className={settingsUi.footer}>
          {source === 'github' && browser === 'chrome'
            ? 'After downloading, extract the ZIP and choose the extracted folder in chrome://extensions with Developer mode enabled. GitHub ZIP installs do not update automatically.'
            : source === 'github'
              ? 'This unsigned Firefox ZIP is for testing in Firefox Developer Edition or Nightly. For a permanent Firefox Stable install, wait for the Mozilla listing.'
              : chosen.href
                ? 'Approved store installs receive browser-managed updates automatically.'
                : 'Firefox installation from the store will be available after the desktop add-on passes Mozilla review.'}
        </p>

        <h2 className={settingsUi.sectionHeader}>Automatic updates</h2>
        <p className={settingsUi.footer}>
          Store installs receive browser-managed updates. GitHub ZIP installs need manual downloads and replacement. Firefox
          Stable updates will be managed by Mozilla after the nuvx listing is approved.
        </p>
        <p className={settingsUi.footer}>
          Spotify, Discord and Listen Together are set up on the{' '}
          <PrefetchLink to="/extension" className={settingsUi.inlineLink}>
            nuvx Extension
          </PrefetchLink>{' '}
          page once the extension is installed.
        </p>
      </div>
    </div>
  );
}
