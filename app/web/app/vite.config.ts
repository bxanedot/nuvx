import { readFileSync } from 'node:fs';
import { fileURLToPath, URL } from 'node:url';
import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

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

export default defineConfig({
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
});
