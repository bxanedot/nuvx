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
