/** The web player's production origin. */
export const PRODUCTION_ORIGIN = 'https://web.nuvx.lol';

/** The same site at its Cloudflare Pages address (the production branch's deploy). */
const PAGES_ORIGIN = 'https://nuvx.pages.dev';

/** The Vite dev server (web/app/vite.config.ts pins the port). */
const DEV_ORIGIN = 'http://localhost:5173';

/**
 * The web app origins this extension serves. Every message is checked against
 * this list, and it becomes externally_connectable (Chromium), the relay's
 * content-script matches (Firefox) and the DNR rules' initiatorDomains.
 *
 * web.nuvx.lol and its pages.dev address always, and the dev server too except in a store build
 * (NUVX_STORE_BUILD=1), so a published extension never answers a page on
 * localhost. NUVX_SITE_ORIGINS (comma-separated) replaces the list. The
 * first origin is the one the popup and homepage_url link to.
 */
export const SITE_ORIGINS: string[] = (
  process.env.NUVX_SITE_ORIGINS ??
  [PRODUCTION_ORIGIN, PAGES_ORIGIN, ...(process.env.NUVX_STORE_BUILD === '1' ? [] : [DEV_ORIGIN])].join(',')
)
  .split(',')
  .map((o) => o.trim().replace(/\/+$/, ''))
  .filter(Boolean);

/**
 * Match patterns can't carry a port, so `http://localhost:5173` becomes
 * `http://localhost/*`. The exact origin, port included, is still enforced at
 * runtime against SITE_ORIGINS.
 */
export function matchPatternsFor(origins: string[]): string[] {
  return [...new Set(origins.map((o) => {
    const url = new URL(o);
    return `${url.protocol}//${url.hostname}/*`;
  }))];
}
