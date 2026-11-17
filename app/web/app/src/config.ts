const defaultPartyServerUrl = 'https://nuvx-listen.lylicht.workers.dev';

export const config = {
  chromiumExtensionId: import.meta.env.VITE_EXTENSION_ID_CHROMIUM || DEV_EXTENSION_ID,
  androidDownloadUrl: import.meta.env.VITE_ANDROID_APP_URL || 'https://www.nuvx.lol/api/download?platform=android',
  tvDownloadUrl: 'https://www.nuvx.lol/api/download?platform=tv',
  autoDownloadUrl: 'https://www.nuvx.lol/api/download?platform=auto',
  wearDownloadUrl: 'https://www.nuvx.lol/api/download?platform=wear',
  iosDownloadUrl: 'https://www.nuvx.lol/api/download?platform=ios',
  partyServerUrl: import.meta.env.VITE_PARTY_SERVER_URL?.trim() || defaultPartyServerUrl,
} as const;
