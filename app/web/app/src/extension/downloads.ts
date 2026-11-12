import type { ComponentType } from 'react';
import { FaChrome, FaFirefoxBrowser } from 'react-icons/fa';

export type Browser = 'chrome' | 'firefox';

export interface Download {
  browser: Browser;
  title: string;
  detail: string;
  href: string | null;
  Icon: ComponentType<{ size?: number; className?: string }>;
}

const CHROME_STORE_URL = 'https://chromewebstore.google.com/detail/ekepdbikjojbkiigoomngmkeohlkgdjp';

export function downloadsFor(): Download[] {
  return [
    {
      browser: 'chrome',
      title: 'Install from Chrome Web Store',
      detail: 'Chrome, Edge, Brave and other Chromium browsers · updates install automatically',
      href: CHROME_STORE_URL,
      Icon: FaChrome,
    },
    {
      browser: 'firefox',
      title: 'Firefox Add-ons listing pending review',
      detail: 'Desktop Firefox · install and automatic updates will be available after approval',
      href: null,
      Icon: FaFirefoxBrowser,
    },
  ];
}

export const currentBrowser: Browser = /firefox/i.test(navigator.userAgent) ? 'firefox' : 'chrome';
