import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('..', import.meta.url));
const registry = readFileSync(join(root, 'src/components/icons/SfSymbol.tsx'), 'utf8');
const after = registry.split('export const SF_SYMBOL_ASSETS = ')[1];
if (!after) throw new Error('SF Symbols registry not found');
const manifest = after.split(' as const;')[0];
if (!manifest) throw new Error('SF Symbols registry is malformed');
const assets = JSON.parse(manifest);

for (const [symbol, file] of Object.entries(assets)) {
  const svg = readFileSync(join(root, 'public/sf-symbols', `${file}.svg`), 'utf8');
  if (!svg.startsWith('<svg ') || !svg.includes(`data-symbol="${symbol}"`) || !svg.includes('<path ')) {
    throw new Error(`Invalid SFStudio glyph mapping: ${symbol} -> ${file}.svg`);
  }
}
console.log(`Verified ${Object.keys(assets).length} SFStudio glyphs for the web player.`);

const animationSource = readFileSync(join(root, 'src/components/icons/SfSymbol.tsx'), 'utf8');
const animationCSS = readFileSync(join(root, 'src/styles/global.css'), 'utf8');
for (const effect of ['bounce', 'pulse', 'variableColor', 'wiggle']) {
  if (!animationSource.includes(effect) || !animationCSS.includes('data-sf-effect="' + effect + '"')) {
    throw new Error('SFStudio effect missing from web renderer: ' + effect);
  }
}
if (!animationCSS.includes('prefers-reduced-motion: reduce')) {
  throw new Error('SFStudio effects must respect reduced motion');
}
const radio = readFileSync(join(root, 'public/sf-symbols/radio.svg'), 'utf8');
if (!radio.includes('data-variable=') || !radio.includes('data-group=')) {
  throw new Error('Radio animated layers are missing their SFStudio metadata');
}

const symbolSource = readFileSync(join(root, 'src/components/icons/SfSymbol.tsx'), 'utf8');
if (!symbolSource.includes('SF_SYMBOL_VISUAL_SCALE = 0.8') || !symbolSource.includes("data-sf-renderer={geometry ? 'layers' : 'mask'}")) {
  throw new Error('Web SF Symbols must render at 80% visual scale with a mask animation fallback');
}
if (!animationCSS.includes('data-sf-renderer="mask"') || !animationCSS.includes('button:not(:disabled):hover')) {
  throw new Error('SF Symbol hover and trigger animations must render without SVG layer support');
}

// Keep distinct SFStudio symbols for Albums and Playlists, and maintain the Settings-only Listen Together entry.
const navSource = readFileSync(join(root, 'src/components/icons/NavIcons.tsx'), 'utf8');
const sidebarSource = readFileSync(join(root, 'src/shell/Sidebar.tsx'), 'utf8');
const settingsSource = readFileSync(join(root, 'src/pages/SettingsPage.tsx'), 'utf8');
if (!navSource.includes("AlbumsIcon = symbol('square.stack.3d.up.fill'") || !navSource.includes("AllPlaylistsIcon = symbol('square.stack.fill'")) {
  throw new Error('Albums and Playlists must have distinct SFStudio icons');
}
if (sidebarSource.includes("label: 'Listen Together'")) {
  throw new Error('Listen Together must not appear as a top-level navigation item');
}
if (!settingsSource.includes('to="/settings/listen-together"')) {
  throw new Error('Listen Together must remain accessible through Settings');
}

// A stacked-album glyph must have three distinct, independently animatable SFStudio outlines.
const albumSvg = readFileSync(join(root, 'public/sf-symbols/square_stack_3d_up_fill.svg'), 'utf8');
const albumGroups = [...albumSvg.matchAll(/data-group="([0-9]+)"/g)].map((m) => Number(m[1]));
if (albumGroups.length !== 3 || albumGroups.join(',') !== '0,1,2' || !albumSvg.includes('data-symbol="square.stack.3d.up.fill"')) {
  throw new Error('Album glyph must use the three separate layers from SFStudio');
}
