import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const publicDir = fileURLToPath(new URL('../../../public/', import.meta.url));

describe('bundled AutoMix audio model licenses', () => {
  it('ships MIT attributions alongside both public model assets', () => {
    const notices = readFileSync(join(publicDir, 'licenses/THIRD_PARTY_NOTICES.txt'), 'utf8');
    const license = readFileSync(join(publicDir, 'licenses/LICENSE-MIT-Model-Weights.txt'), 'utf8');

    for (const [model, holder] of [
      ['beat_this_final0_q8.onnx', 'Copyright (c) 2024 Institute of Computational Perception'],
      ['vocals_umxhq_int8.onnx', 'Copyright (c) 2019 Inria'],
    ] as const) {
      expect(existsSync(join(publicDir, 'models', model))).toBe(true);
      expect(notices).toContain(model);
      expect(license).toContain(holder);
    }
    expect(license).toContain('Permission is hereby granted, free of charge');
    expect(license).toContain('The above copyright notice and this permission notice');
  });
});
