// SPDX-License-Identifier: GPL-3.0-only
import { describe, expect, it } from 'vitest';
import { downloadsFor } from './downloads';

describe('GitHub extension downloads', () => {
  it('uses the versioned packages from the combined release', () => {
    const downloads = downloadsFor().filter((download) => download.source === 'github');
    expect(downloads.map((download) => download.href)).toEqual([
      `https://github.com/bxanedot/nuvx/releases/download/v${EXTENSION_VERSION}/nuvx-extension-chrome-${EXTENSION_VERSION}.zip`,
      `https://github.com/bxanedot/nuvx/releases/download/v${EXTENSION_VERSION}/nuvx-extension-firefox-${EXTENSION_VERSION}.zip`,
    ]);
  });
});
