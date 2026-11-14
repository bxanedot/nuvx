import { mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { unzipSync, zipSync } from 'fflate';

const output = fileURLToPath(new URL('../.output/', import.meta.url));
const target = fileURLToPath(new URL('../../app/public/downloads/', import.meta.url));
mkdirSync(target, { recursive: true });
for (const name of readdirSync(target)) if (/^nuvx-extension-.*\.zip$/.test(name)) rmSync(join(target, name));

function filesUnder(dir) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? filesUnder(path) : [path];
  });
}

const builds = { chrome: 'chrome-mv3', firefox: 'firefox-mv3' };
const files = {};
let version;

for (const [browser, folder] of Object.entries(builds)) {
  const root = join(output, folder);
  const manifest = JSON.parse(readFileSync(join(root, 'manifest.json'), 'utf8'));
  if (version && manifest.version !== version) {
    throw new Error(`Chrome and Firefox builds disagree on the version (${version} vs ${manifest.version}); rebuild both`);
  }
  version = manifest.version;

  const parent = `nuvx-extension-${browser} (${manifest.version})`;
  const prefix = browser === 'chrome' ? `${parent}/` : '';
  const entries = {};
  for (const file of filesUnder(root)) {
    entries[`${prefix}${relative(root, file).split(sep).join('/')}`] = readFileSync(file);
  }
  const name = `${parent}.zip`;
  const archive = zipSync(entries, { level: 9 });
  if (browser === 'firefox' && !unzipSync(archive)['manifest.json']) {
    throw new Error('Firefox ZIP must have manifest.json at the archive root for direct temporary installation.');
  }
  writeFileSync(join(target, name), archive);
  files[browser] = name;
  console.log(`${folder} -> app/public/downloads/${name} (${prefix || 'archive root'}, v${version})`);
}

writeFileSync(
  join(target, 'extension.json'),
  `${JSON.stringify({ version, builtAt: new Date().toISOString(), files }, null, 2)}\n`,
);
console.log(`app/public/downloads/extension.json (v${version})`);
