import { describe, it, expect } from 'vitest';
import { safeCanvasUrl } from './canvasUrl';

describe('animated canvas URL fallback', () => {
  it('accepts a valid HTTPS canvas and rejects missing, unsafe and invalid media URLs', () => {
    expect(safeCanvasUrl('https://cdn.example.test/canvas.mp4')).toBe('https://cdn.example.test/canvas.mp4');
    for (const input of [
      null, '', 'invalid-url', '/relative.mp4',
      'http://cdn.example.test/canvas.mp4', 'javascript:alert(1)',
      'data:video/mp4;base64,AAAA', 'blob:https://cdn.example.test/id',
      'https://user:password@cdn.example.test/canvas.mp4',
      'https://cdn.example.test:8443/canvas.mp4',
      'https://cdn.example.test/' + 'a'.repeat(4096),
    ]) {
      expect(safeCanvasUrl(input)).toBeNull();
    }
  });
});
