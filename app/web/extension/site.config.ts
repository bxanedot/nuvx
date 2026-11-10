export const PRODUCTION_ORIGIN = 'https://web.nuvx.lol';

const PAGES_ORIGIN = 'https://nuvx.pages.dev';

const DEV_ORIGIN = 'http://localhost:5173';

export const SITE_ORIGINS: string[] = (
  process.env.NUVX_SITE_ORIGINS ??
  [PRODUCTION_ORIGIN, PAGES_ORIGIN, ...(process.env.NUVX_STORE_BUILD === '1' ? [] : [DEV_ORIGIN])].join(',')
)
  .split(',')
  .map((o) => o.trim().replace(/\/+$/, ''))
  .filter(Boolean);

export function matchPatternsFor(origins: string[]): string[] {
  return [...new Set(origins.map((o) => {
    const url = new URL(o);
    return `${url.protocol}//${url.hostname}/*`;
  }))];
}
