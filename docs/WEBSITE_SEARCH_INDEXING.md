# Website search indexing

The public site is `https://www.nuvx.lol`.

- [`robots.txt`](https://www.nuvx.lol/robots.txt) allows crawling and advertises `https://www.nuvx.lol/sitemap.xml`.
- The XML sitemap lists the canonical public pages. Keep it accurate when pages are added, removed, or moved, and update `lastmod` only when page content changes.
- The IndexNow key file is tracked at `core/website/045adc84f9a240278c33ee11e35eff56.txt` and served at `https://www.nuvx.lol/045adc84f9a240278c33ee11e35eff56.txt`. Keep the filename and file contents equal to the same key so future static-site deployments retain it.

After deploying a page addition, removal, or meaningful content update, submit the changed canonical URL through IndexNow. A `200` response means the submission was accepted; a first `202` means it was received while the key is verified. Neither response guarantees inclusion in search results. Use the site owner's Bing Webmaster Tools account to monitor crawl/index status or submit the sitemap directly.

## Current status (2026-11-15)

- The sitemap, robots file, and IndexNow key returned HTTP 200 from production.
- The five canonical pages were submitted through IndexNow; the initial request returned `202`, and a retry returned `200`.
- After publishing the v1.0.0 site changes, the homepage, docs, and changelog were resubmitted through IndexNow and accepted with HTTP `200`.
- A direct Bing `site:www.nuvx.lol` search still returns no results. The homepage appears in a separate public web search index, but that does not confirm Bing indexing. Bing controls crawl timing and inclusion; monitor the site's Bing Webmaster Tools account.
