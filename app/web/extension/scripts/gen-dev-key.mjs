import { createHash, generateKeyPairSync } from 'node:crypto';
import { writeFileSync } from 'node:fs';

const { publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
const der = publicKey.export({ type: 'spki', format: 'der' });

const id = [...createHash('sha256').update(der).digest('hex').slice(0, 32)]
  .map((c) => String.fromCharCode('a'.charCodeAt(0) + parseInt(c, 16)))
  .join('');

writeFileSync(new URL('../dev-key/public-key.txt', import.meta.url), `${der.toString('base64')}\n`);
writeFileSync(new URL('../dev-key/extension-id.txt', import.meta.url), `${id}\n`);
console.log(`Development extension id: ${id}`);
