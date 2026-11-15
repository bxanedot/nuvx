# Website search indexing

The public site is `https://www.nuvx.lol`.

- [`robots.txt`](https://www.nuvx.lol/robots.txt) allows crawling and advertises `https://www.nuvx.lol/sitemap.xml`.
- The XML sitemap lists the canonical public pages. Keep it accurate when pages are added, removed, or moved, and update `lastmod` only when page content changes.
- The IndexNow key file is hosted at `https://www.nuvx.lol/045adc84f9a240278c33ee11e35eff56.txt`. Its filename and contents must remain the same key.

After deploying a page addition, removal, or meaningful content update, submit the changed canonical URL through IndexNow. A `200` response means the submission was accepted; a first `202` means it was received while the key is verified. Neither response guarantees inclusion in search results. Use the site owner's Bing Webmaster Tools account to monitor crawl/index status or submit the sitemap directly.

## Current status (2026-11-15)

- The sitemap, robots file, and IndexNow key returned HTTP 200 from production.
- The five canonical pages were submitted through IndexNow; the initial request returned `202`, and a retry returned `200`.
- A Bing `site:www.nuvx.lol` search returned no results at that time, so search listing has not been confirmed. Bing controls crawl timing and inclusion.
