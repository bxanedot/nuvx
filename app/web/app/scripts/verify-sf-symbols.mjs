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
