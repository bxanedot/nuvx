export const config = {
  chromiumExtensionId: import.meta.env.VITE_EXTENSION_ID_CHROMIUM || DEV_EXTENSION_ID,
  extensionChromeUrl: import.meta.env.VITE_EXTENSION_CHROME_URL || '/downloads/nuvx-extension-chrome.zip',
  extensionFirefoxUrl: import.meta.env.VITE_EXTENSION_FIREFOX_URL || '/downloads/nuvx-extension-firefox.zip',
  extensionManifestUrl: import.meta.env.VITE_EXTENSION_MANIFEST_URL || '/downloads/extension.json',
  appDownloadUrl: import.meta.env.VITE_ANDROID_APP_URL || 'https://github.com/bxanedot/nuvx/releases/latest',
  partyServerUrl: import.meta.env.VITE_PARTY_SERVER_URL ?? '',
} as const;
