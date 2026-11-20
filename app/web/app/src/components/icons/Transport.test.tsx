import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { PlayPauseIcon } from './Transport';

describe('PlayPauseIcon', () => {
  it('keeps both glyphs so CSS can morph between them, and marks which one shows', () => {
    const playing = renderToStaticMarkup(<PlayPauseIcon playing size={20} />);
    expect(playing).toContain('data-playing="true"');
    // Static symbols render as CSS-mask spans (the <svg> layers only load for animated effects).
    expect(playing.match(/data-sf-symbol="(?:play|pause)\.fill"/g)).toHaveLength(2);
    expect(renderToStaticMarkup(<PlayPauseIcon playing={false} />)).toContain('data-playing="false"');
  });
});
