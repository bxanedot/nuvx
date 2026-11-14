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
          <a className={settingsUi.download} href={config.androidDownloadUrl}>
            <Download size={25} className="text-primary" />
            <span className={settingsUi.downloadText}>
              <span className={settingsUi.downloadTitle}>Android · universal GMS APK</span>
              <span className={settingsUi.downloadDetail}>Installs on arm32, arm64 and x86_64 phones and tablets</span>
            </span>
            <ChevronRight size={16} strokeWidth={2.4} className={settingsUi.chevron} />
          </a>
          <a className={settingsUi.download} href={config.androidFossDownloadUrl}>
            <Download size={25} className="text-primary" />
            <span className={settingsUi.downloadText}>
              <span className={settingsUi.downloadTitle}>Android · universal FOSS APK</span>
              <span className={settingsUi.downloadDetail}>Installs on arm32, arm64 and x86_64 phones and tablets</span>
            </span>
            <ChevronRight size={16} strokeWidth={2.4} className={settingsUi.chevron} />
          </a>
          <a className={settingsUi.download} href={config.tvDownloadUrl}>
            <Download size={25} className="text-primary" />
            <span className={settingsUi.downloadText}>
              <span className={settingsUi.downloadTitle}>Android TV · universal GMS APK</span>
              <span className={settingsUi.downloadDetail}>Installs on arm32, arm64 and x86_64 Android TV devices</span>
            </span>
            <ChevronRight size={16} strokeWidth={2.4} className={settingsUi.chevron} />
          </a>
          <a className={settingsUi.download} href={config.tvFossDownloadUrl}>
            <Download size={25} className="text-primary" />
            <span className={settingsUi.downloadText}>
              <span className={settingsUi.downloadTitle}>Android TV · universal FOSS APK</span>
              <span className={settingsUi.downloadDetail}>Installs on arm32, arm64 and x86_64 Android TV devices</span>
            </span>
            <ChevronRight size={16} strokeWidth={2.4} className={settingsUi.chevron} />
          </a>
          <a className={settingsUi.download} href={config.wearDownloadUrl}>
            <Download size={25} className="text-primary" />
            <span className={settingsUi.downloadText}>
              <span className={settingsUi.downloadTitle}>Wear OS companion APK</span>
              <span className={settingsUi.downloadDetail}>Requires Wear OS 3+ and a paired Android phone running nuvx</span>
            </span>
            <ChevronRight size={16} strokeWidth={2.4} className={settingsUi.chevron} />
          </a>
          <a className={settingsUi.download} href={config.desktopDownloadUrl} target="_blank" rel="noreferrer">
            <Download size={25} className="text-primary" />
            <span className={settingsUi.downloadText}>
              <span className={settingsUi.downloadTitle}>Windows, macOS and Linux</span>
              <span className={settingsUi.downloadDetail}>Universal desktop installers on GitHub</span>
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
