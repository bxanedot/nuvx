import type { ComponentType } from 'react';
import { FaChrome, FaFirefoxBrowser } from 'react-icons/fa';

export type Browser = 'chrome' | 'firefox';
export type InstallSource = 'store' | 'github';

export interface Download {
  browser: Browser;
  source: InstallSource;
  title: string;
  detail: string;
  href: string | null;
  Icon: ComponentType<{ size?: number; className?: string }>;
}

const CHROME_STORE_URL = 'https://chromewebstore.google.com/detail/ekepdbikjojbkiigoomngmkeohlkgdjp';
const GITHUB_ASSETS = `https://github.com/bxanedot/nuvx/releases/download/v${EXTENSION_VERSION}`;

export function downloadsFor(): Download[] {
  return [
    {
      browser: 'chrome',
      source: 'store',
      title: 'Install from Chrome Web Store',
      detail: 'Chrome, Edge, Brave and other Chromium browsers · updates install automatically',
      href: CHROME_STORE_URL,
      Icon: FaChrome,
    },
    {
      browser: 'chrome',
      source: 'github',
      title: 'Download Chrome extension ZIP from GitHub',
      detail: 'Manual install · extract the ZIP and load its folder at chrome://extensions',
      href: `${GITHUB_ASSETS}/nuvx-extension-chrome-${EXTENSION_VERSION}.zip`,
      Icon: FaChrome,
    },
    {
      browser: 'firefox',
      source: 'store',
      title: 'Firefox Add-ons listing pending review',
      detail: 'Desktop Firefox · install and automatic updates will be available after approval',
      href: null,
      Icon: FaFirefoxBrowser,
    },
    {
      browser: 'firefox',
      source: 'github',
      title: 'Download Firefox extension ZIP from GitHub',
      detail: 'Temporary install · Load this ZIP in Firefox Developer Edition or Nightly',
      href: `${GITHUB_ASSETS}/nuvx-extension-firefox-${EXTENSION_VERSION}.zip`,
      Icon: FaFirefoxBrowser,
    },
  ];
}

export const currentBrowser: Browser = /firefox/i.test(navigator.userAgent) ? 'firefox' : 'chrome';
