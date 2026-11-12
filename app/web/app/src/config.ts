export const config = {
  chromiumExtensionId: import.meta.env.VITE_EXTENSION_ID_CHROMIUM || DEV_EXTENSION_ID,
  appDownloadUrl: import.meta.env.VITE_ANDROID_APP_URL || 'https://github.com/bxanedot/nuvx/releases/latest',
  partyServerUrl: import.meta.env.VITE_PARTY_SERVER_URL ?? '',
} as const;
