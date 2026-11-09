import assert from "node:assert/strict";
import { test } from "node:test";
import { countReleaseDownloads } from "../src/downloads.ts";

test("counts app packages across release pages and skips checksums and drafts", async () => {
  const pages = [
    Array.from({ length: 100 }, () => ({ assets: [
      { name: "NuvxTV-v0.2.0.apk", download_count: 2 },
      { name: "nuvx-setup-0.2.0.exe", download_count: 3 },
      { name: "nuvx-0.2.0-linux-x86_64.tar.gz", download_count: 4 },
      { name: "nuvx-setup-0.2.0-macos-aarch64.dmg", download_count: 5 },
      { name: "SHA256SUMS", download_count: 100 },
    ] })),
    [
      { assets: [{ name: "nuvx-portable-0.2.0.zip", download_count: 8 }] },
      { draft: true, assets: [{ name: "nuvx-v0.3.0.apk", download_count: 100 }] },
    ],
  ];
  const get: typeof fetch = async (url, options) => {
    const request = new URL(String(url));
    assert.equal(request.origin, "https://api.github.com");
    assert.equal(new Headers(options?.headers).get("authorization"), "Bearer fixture-token");
    assert.equal(options?.redirect, "manual");
    return Response.json(pages[Number(request.searchParams.get("page")) - 1]);
  };
  assert.equal(await countReleaseDownloads(get, "fixture-token"), 1408);
});

test("an unavailable GitHub API cannot report zero downloads", async () => {
  await assert.rejects(countReleaseDownloads(async () => new Response(null, { status: 403 })), /403/);
});
