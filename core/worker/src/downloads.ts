const RELEASES_URL = "https://api.github.com/repos/bxanedot/nuvx/releases";
// shortcut: matches current release filenames, update when adding a package format or naming scheme.
const APP_PACKAGE = /^nuvx(?:tv|wear)?-(?:(?:setup|portable)-)?v?\d.*\.(?:apk|exe|dmg|zip|tar\.gz)$/i;
const PAGE_SIZE = 100;

type Release = { draft: boolean; assets: { name: string; download_count: number }[] };

export async function countReleaseDownloads(get: typeof fetch, token?: string): Promise<number> {
  const headers = new Headers({ "user-agent": "nuvx-listen", accept: "application/vnd.github+json" });
  if (token) headers.set("authorization", `Bearer ${token}`);
  let total = 0;
  for (let page = 1; ; page++) {
    const response = await get(`${RELEASES_URL}?per_page=${PAGE_SIZE}&page=${page}`, {
      headers,
      redirect: "manual",
      signal: AbortSignal.timeout(5000),
    });
    if (!response.ok) throw new Error(`GitHub releases returned ${response.status}`);
    const releases = await response.json<Release[]>();
    for (const release of releases) {
      if (release.draft) continue;
      for (const asset of release.assets) {
        if (!APP_PACKAGE.test(asset.name)) continue;
        if (!Number.isSafeInteger(asset.download_count) || asset.download_count < 0) throw new Error("Invalid release download count");
        total += asset.download_count;
      }
    }
    if (releases.length < PAGE_SIZE) return total;
  }
}
