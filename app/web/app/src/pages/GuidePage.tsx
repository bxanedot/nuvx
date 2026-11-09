import { ChevronRight } from 'lucide-react';
import { useState } from 'react';
import { PageHeader } from '@/components/PageHeader';
import { PrefetchLink } from '@/components/PrefetchLink';
import { currentBrowser, downloadsFor, type Browser } from '@/extension/downloads';
import { useExtension } from '@/extension/useExtension';
import { usePublishedExtension } from '@/extension/useExtensionUpdate';
import { settingsUi } from './settingsStyles';

/** Step-by-step install guide for the extension, one browser at a time. */
export default function GuidePage() {
  const status = useExtension();
  const downloads = downloadsFor(usePublishedExtension());
  const [browser, setBrowser] = useState<Browser>(currentBrowser);
  const chosen = downloads.find((d) => d.browser === browser)!;

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
          {downloads.map((d) => (
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
        <section className={settingsUi.group}>
          <a className={settingsUi.download} href={chosen.href} download>
            <chosen.Icon size={28} className="text-primary" />
            <span className={settingsUi.downloadText}>
              <span className={settingsUi.downloadTitle}>{chosen.title}</span>
              <span className={settingsUi.downloadDetail}>{chosen.detail}</span>
            </span>
            <ChevronRight size={16} strokeWidth={2.4} className={settingsUi.chevron} />
          </a>
        </section>
        <ol className={settingsUi.steps}>
          {chosen.steps.map((step) => (
            <li key={step}>{step}</li>
          ))}
        </ol>

        <h2 className={settingsUi.sectionHeader}>Update it later</h2>
        <ol className={settingsUi.steps}>
          <li>When a new version is out, nuvx Extension in the sidebar shows Update.</li>
          {chosen.updateSteps.map((step) => (
            <li key={step}>{step}</li>
          ))}
        </ol>
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
