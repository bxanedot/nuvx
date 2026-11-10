import { isGap, type LyricLine, type LyricWord } from './model';

const escape = (text: string) =>
  text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

const clock = (ms: number) => (Math.max(0, ms) / 1000).toFixed(3);

function spansOf(words: LyricWord[]): string {
  return words
    .map((word) => {
      if (!word.syllables.length) {
        return `<span begin="${clock(word.startMs)}" end="${clock(word.endMs)}">${escape(word.text)}</span>`;
      }
      return word.syllables
        .map(
          (s) =>
            `<span begin="${clock(s.startMs)}" end="${clock(s.endMs)}">${escape(word.text.slice(s.startChar, s.endChar))}</span>`,
        )
        .join('');
    })
    .join(' ');
}

export function writeTtml(lines: LyricLine[], language = 'en'): string {
  const sung = lines.filter((l) => !isGap(l));
  const timed = sung.some((l) => l.timeMs > 0 || l.words.length);
  const wordTimed = sung.some((l) => l.words.length);
  const duet = sung.some((l) => l.alignment === 'end');

  const paragraphs = sung.map((l, index) => {
    const agent = duet ? ` ttm:agent="${l.alignment === 'end' ? 'v2' : 'v1'}"` : '';
    if (!timed) return `<p${agent}>${escape(l.text)}</p>`;
    const next = sung[index + 1]?.timeMs;
    if (l.words.length) {
      const lastEnd = Math.max(l.words.at(-1)!.endMs, l.background?.words.at(-1)?.endMs ?? 0);
      const background = l.background?.words.length
        ? ` <span ttm:role="x-bg">${spansOf(l.background.words)}</span>`
        : '';
      return `<p begin="${clock(l.timeMs)}" end="${clock(lastEnd)}"${agent}>${spansOf(l.words)}${background}</p>`;
    }
    const text = l.background ? `${l.text} ${l.background.text}` : l.text;
    const end = l.sungUntilMs ?? next ?? l.timeMs + 5000;
    return `<p begin="${clock(l.timeMs)}" end="${clock(Math.max(end, l.timeMs))}"${agent}>${escape(text)}</p>`;
  });

  const agents = duet ? '<ttm:agent type="person" xml:id="v1"/><ttm:agent type="other" xml:id="v2"/>' : '';
  return (
    `<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" ` +
    `xmlns:itunes="http://music.apple.com/lyric-ttml-internal" xml:lang="${escape(language)}" ` +
    `itunes:timing="${wordTimed ? 'Word' : timed ? 'Line' : 'None'}">` +
    `<head><metadata>${agents}</metadata></head><body><div>${paragraphs.join('')}</div></body></tt>`
  );
}
