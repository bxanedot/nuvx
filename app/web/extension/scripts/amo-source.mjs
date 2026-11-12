import { execFileSync, spawnSync } from 'node:child_process';
import { readFileSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';

const extensionDir = fileURLToPath(new URL('../', import.meta.url));
const repoRoot = fileURLToPath(new URL('../../../../', import.meta.url));
const pkg = JSON.parse(readFileSync(join(extensionDir, 'package.json'), 'utf8'));
const mode = process.argv[2] ?? 'build';

if (mode === 'build') {
  const result = spawnSync('pnpm', ['exec', 'wxt', 'build', '-b', 'firefox'], {
    cwd: extensionDir,
    env: { ...process.env, NUVX_STORE_BUILD: '1' },
    stdio: 'inherit',
    shell: process.platform === 'win32',
  });
  if (result.error) throw result.error;
  if (result.status !== 0) process.exit(result.status ?? 1);

  const manifest = JSON.parse(readFileSync(join(extensionDir, '.output/firefox-mv3/manifest.json'), 'utf8'));
  if (
    manifest.version !== pkg.version ||
    manifest.browser_specific_settings?.gecko?.id !== 'extension@nuvx.lol' ||
    manifest.browser_specific_settings?.gecko?.strict_min_version !== '140.0'
  ) {
    throw new Error('Firefox store build manifest does not match the expected version or Firefox settings.');
  }
  console.log(`Built Firefox ${manifest.version} for desktop Firefox 140+ in .output/firefox-mv3/`);
} else if (mode === 'source') {
  const archive = join(repoRoot, `nuvx-firefox-source-${pkg.version}.zip`);
  execFileSync('git', [
    'archive', '--format=zip', `--output=${archive}`, 'HEAD',
    'app/web/package.json',
    'app/web/pnpm-lock.yaml',
    'app/web/pnpm-workspace.yaml',
    'app/web/extension',
    'app/web/shared',
  ], { cwd: repoRoot, stdio: 'inherit' });

  if (statSync(archive).size > 200 * 1024 * 1024) {
    throw new Error('AMO source archive exceeds the 200 MB upload limit.');
  }
  console.log(`Created ${archive} (${(statSync(archive).size / 1024 / 1024).toFixed(1)} MB)`);
} else {
  throw new Error('Usage: node scripts/amo-source.mjs [build|source]');
}
