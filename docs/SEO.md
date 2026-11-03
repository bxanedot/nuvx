# Google Search Console setup

The website SEO files are prepared, but Google Search Console verification must be completed by bxane while signed in to the Google account that should own the property. No verification token is present in the repository.

## Recommended: Domain property

1. Sign in to [Google Search Console](https://search.google.com/search-console/) as bxane and choose **Add property**.
2. Choose **Domain** and enter `nuvx.lol` (without a scheme or path).
3. Google will show a DNS TXT record. Add that exact record at the DNS host for `nuvx.lol`, preserving the host/name and value Google provides.
4. Return to Search Console and select **Verify**. DNS propagation can take time; retry later if Google has not seen the record yet.
5. In the verified property, open **Sitemaps**, enter `https://www.nuvx.lol/sitemap.xml`, and submit it.
6. Use **URL inspection** for `https://www.nuvx.lol/` and choose **Request indexing**. Repeat for `/docs`, `/terms`, and `/privacy` if desired.

## Alternative: URL-prefix property

1. In Search Console choose **Add property**, select **URL prefix**, and enter `https://www.nuvx.lol`.
2. Choose **HTML tag** verification. In the provided `<meta name="google-site-verification" content="...">` tag, copy only the value of `content`.
3. Paste that value at the clearly marked Google Search Console comment in `website/index.html`'s `<head>`, as a real meta tag, or send the value to Claude to add it. Do not publish Google's sample or a made-up token.
4. Deploy the change through the normal reviewed `main` workflow, then return to Search Console and choose **Verify**.
5. Submit `https://www.nuvx.lol/sitemap.xml` under **Sitemaps** and request indexing for the home page through **URL inspection**.

The site uses clean URLs, so the public pages are `/`, `/docs`, `/terms`, and `/privacy`. Google decides when and whether to index them; a request is not a guarantee of inclusion or ranking.
