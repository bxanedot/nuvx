import { readFileSync } from 'node:fs';
import { fileURLToPath, URL } from 'node:url';
import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { defineConfig, loadEnv } from 'vite';

function devExtensionId(): string {
  try {
    return readFileSync(new URL('../extension/dev-key/extension-id.txt', import.meta.url), 'utf8').trim();
  } catch {
    return '';
  }
}

function extensionVersion(): string {
  return JSON.parse(readFileSync(new URL('../extension/package.json', import.meta.url), 'utf8')).version;
}

function requirePartyServer(mode: string): void {
  const env = loadEnv(mode, fileURLToPath(new URL('.', import.meta.url)), 'VITE_');
  if (!env.VITE_PARTY_SERVER_URL?.trim()) {
    throw new Error('VITE_PARTY_SERVER_URL is not set (app/.env.local, or the Cloudflare Pages environment).');
  }
}

export default defineConfig(({ command, mode }) => {
  if (command === 'build') requirePartyServer(mode);
  return {
    plugins: [react(), tailwindcss()],
    define: {
      DEV_EXTENSION_ID: JSON.stringify(devExtensionId()),
      EXTENSION_VERSION: JSON.stringify(extensionVersion()),
    },
    resolve: {
      alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
    },
    optimizeDeps: { exclude: ['essentia.js'] },
    worker: { format: 'es' },
    server: {
      port: 5173,
      strictPort: true,
    },
  };
});
