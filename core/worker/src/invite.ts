// The page behind https://nuvx.lol/invite/<code> (the website rewrites it here): opens the app on the party.

export type InviteInfo = { code: string; active: boolean; title?: string; artist?: string; memberCount?: number };

const esc = (s: string) => s.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);

export function invitePage(info: InviteInfo, origin: string): string {
  const code = esc(info.code.slice(0, 32));
  const deepLink = `nuvx://party/${encodeURIComponent(info.code)}?server=${encodeURIComponent(origin)}`;
  const intent = `intent://party/${encodeURIComponent(info.code)}?server=${encodeURIComponent(origin)}#Intent;scheme=nuvx;end`;
  const web = `https://web.nuvx.lol/invite/${encodeURIComponent(info.code)}?server=${encodeURIComponent(origin)}`;
  const body = info.active
    ? `<p class="badge"><span class="dot"></span>Listen Together · ${info.memberCount ?? 0} in party</p>
<h1>Join the party</h1>
<p class="sub">Opening nuvx to listen together.</p>
<div class="box"><p class="label">Party code</p><p class="code">${code}</p>${info.title ? `<p class="now">${esc(info.title)} · ${esc(info.artist ?? "")}</p>` : ""}</div>
<a class="btn" id="join" href="${esc(intent)}">Join in nuvx</a>
<a class="btn alt" href="${esc(web)}">Open in the web player</a>
<p class="note">Didn't open? Tap the button. No nuvx yet? <a href="https://github.com/bxanedot/nuvx/releases" rel="noopener">Download it</a>.</p>
<script>setTimeout(function () { location.href = /Android/i.test(navigator.userAgent) ? ${JSON.stringify(intent)} : ${JSON.stringify(deepLink)}; }, 100);</script>`
    : `<p class="badge"><span class="dot off"></span>Party inactive</p>
<h1>Party not found</h1>
<p class="sub">This party code has expired or does not exist.</p>
<div class="box"><p class="label">Party code</p><p class="code dim">${code}</p></div>
<p class="note">Ask the host for a new invite link.</p>`;
  return `<!doctype html>
<html lang="en" data-theme="dark"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>nuvx Listen Together · ${code}</title>
<link rel="stylesheet" href="https://www.nuvx.lol/ambient.css">
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{--accent:#f1adc4;isolation:isolate;color-scheme:dark;accent-color:var(--accent);background:#000;color:#f5f5f5;font-family:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;min-height:100vh;display:flex;align-items:center;justify-content:center;padding:24px}
main{max-width:420px;width:100%;text-align:center;border:1px solid #2a2a2a;border-radius:24px;padding:32px 24px;background:#0c0c0c}
.badge{display:inline-flex;align-items:center;gap:6px;font-size:13px;color:#bbb;border:1px solid #2a2a2a;border-radius:99px;padding:6px 12px;margin-bottom:20px}
.dot{width:8px;height:8px;border-radius:50%;background:var(--accent)}.dot.off{background:#ff5252}
h1{font-size:24px;margin-bottom:8px}.sub{color:#999;margin-bottom:24px}
.box{border:1px solid #2a2a2a;border-radius:16px;padding:16px;margin-bottom:24px}
.label{font-size:12px;color:#777;text-transform:uppercase;letter-spacing:1.5px;margin-bottom:6px}
.code{font:800 32px ui-monospace,Consolas,monospace;letter-spacing:6px}.dim{color:#777}
.now{font-size:14px;color:#ccc;margin-top:10px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.btn{display:block;padding:15px;border-radius:14px;background:var(--accent);color:#000;font-weight:600;text-decoration:none;margin-bottom:10px}
.btn.alt{background:transparent;color:#fff;border:1px solid #3a3a3a}
.btn:focus-visible,a:focus-visible{outline:2px solid var(--accent);outline-offset:2px}
.note{margin-top:14px;font-size:13px;color:#888}.note a{color:var(--accent)}
.ambient-pause{margin-top:12px;color:#bbb}
</style></head><body><div class="nuvx-ambient" aria-hidden="true"></div><main>
${body}
<label class="ambient-pause"><input id="ambient-pause" type="checkbox"> Pause background motion</label>
</main></body></html>`;
}
