import { ChevronRight, Download } from 'lucide-react';
import { PageHeader } from '@/components/PageHeader';
import { downloadsFor } from '@/extension/downloads';
import { config } from '@/config';
import { settingsUi } from './settingsStyles';

export default function DownloadsPage() {
  const extensions = downloadsFor().filter((download) => download.source === 'github');

  return (
    <div className={settingsUi.centered}>
      <PageHeader title="Downloads" />
      <div className={settingsUi.body}>
        <p className={settingsUi.footer}>Choose the nuvx app or a browser extension.</p>

        <h2 className={settingsUi.sectionHeader}>Apps</h2>
        <section className={settingsUi.group}>
          <a className={settingsUi.download} href={config.appDownloadUrl} target="_blank" rel="noreferrer">
            <Download size={25} className="text-primary" />
            <span className={settingsUi.downloadText}>
              <span className={settingsUi.downloadTitle}>App installers</span>
              <span className={settingsUi.downloadDetail}>Android, Windows, macOS and Linux · choose a build on GitHub</span>
            </span>
            <ChevronRight size={16} strokeWidth={2.4} className={settingsUi.chevron} />
          </a>
        </section>

        <h2 className={settingsUi.sectionHeader}>Browser extensions</h2>
        <section className={settingsUi.group}>
          {extensions.map(({ browser, detail, href, Icon }) => (
            <a key={browser} className={settingsUi.download} href={href ?? undefined} target="_blank" rel="noreferrer">
              <Icon size={28} className="text-primary" />
              <span className={settingsUi.downloadText}>
                <span className={settingsUi.downloadTitle}>{browser === 'chrome' ? 'Chrome extension ZIP' : 'Firefox extension ZIP'}</span>
                <span className={settingsUi.downloadDetail}>{detail}</span>
              </span>
              <ChevronRight size={16} strokeWidth={2.4} className={settingsUi.chevron} />
            </a>
          ))}
        </section>
        <p className={settingsUi.footer}>
          GitHub ZIP extensions require manual installation and updates. Firefox Stable requires a Mozilla-approved add-on.
        </p>
      </div>
    </div>
  );
}
