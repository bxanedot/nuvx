import type { ComponentType } from 'react';
import { FaChrome, FaFirefoxBrowser } from 'react-icons/fa';
import { config } from '@/config';
import { downloadUrl, type PublishedExtension } from './useExtensionUpdate';

/* The extension's published builds and how to install them: the Extension page and the Guide page share these. */

export type Browser = 'chrome' | 'firefox';

export interface Download {
  browser: Browser;
  title: string;
  detail: string;
  href: string;
  Icon: ComponentType<{ size?: number; className?: string }>;
  steps: string[];
  updateSteps: string[];
}

/**
 * The two builds, as published (extension.json): the zip and the folder in it
 * carry the version, "nuvx-extension-chrome (0.4.3)". Before that file
 * has loaded, the configured URLs and the unversioned names stand in.
 */
export function downloadsFor(published: PublishedExtension | null): Download[] {
  const folder = (browser: Browser) => `nuvx-extension-${browser}${published ? ` (${published.version})` : ''}`;
  return [
    {
      browser: 'chrome',
      title: 'Download for Chrome',
      detail: 'Chrome, Edge, Brave and other Chromium browsers',
      href: published ? downloadUrl(published.files.chrome) : config.extensionChromeUrl,
      Icon: FaChrome,
      steps: [
        'Choose Download for Chrome above. The zip goes to your Downloads folder.',
        `Extract the zip somewhere you’ll keep it, such as Documents. It holds one folder, ${folder('chrome')}. Don’t delete it: the browser loads nuvx from that folder every time it starts.`,
        'Open chrome://extensions in a new tab (edge://extensions in Edge, brave://extensions in Brave).',
        'Turn on Developer mode, at the top right.',
        `Choose Load unpacked and select the ${folder('chrome')} folder, the one with manifest.json inside.`,
        'Open the puzzle-piece menu in the toolbar and pin nuvx, so its icon stays in view.',
        'Check the status at the top of this page: it turns to Installed.',
        'To use your own account, sign in at music.youtube.com in this browser. nuvx uses that sign-in, and this page never sees your cookies.',
      ],
      updateSteps: [
        `Extract the new zip. It holds ${folder('chrome')}.`,
        'In chrome://extensions, choose Load unpacked and select that folder. It has the same ID, so it takes the old one’s place and keeps your settings.',
        'Delete the old folder, then come back to this page: the version above updates on its own.',
      ],
    },
    {
      browser: 'firefox',
      title: 'Download for Firefox',
      detail: 'Firefox 128 or newer',
      href: published ? downloadUrl(published.files.firefox) : config.extensionFirefoxUrl,
      Icon: FaFirefoxBrowser,
      steps: [
        'Choose Download for Firefox above. The zip goes to your Downloads folder.',
        `Extract the zip somewhere you’ll keep it. It holds one folder, ${folder('firefox')}.`,
        'Open about:debugging#/runtime/this-firefox in a new tab.',
        `Choose Load Temporary Add-on and select manifest.json inside the ${folder('firefox')} folder.`,
        'Check the status at the top of this page: it turns to Installed.',
        'To use your own account, sign in at music.youtube.com in this browser. nuvx uses that sign-in, and this page never sees your cookies.',
        'Firefox removes temporary add-ons when it quits, so repeat steps 3 and 4 after each restart until a signed version is out.',
      ],
      updateSteps: [
        `Extract the new zip. It holds ${folder('firefox')}.`,
        'In about:debugging, choose Load Temporary Add-on and select manifest.json in the new folder.',
        'Come back to this page: the version above updates on its own.',
      ],
    },
  ];
}

/** The browser this page is open in, to put its download first. */
export const currentBrowser: Browser = /firefox/i.test(navigator.userAgent) ? 'firefox' : 'chrome';
