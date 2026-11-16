# nuvx website design notes

## Reference and capture

- Reference: [DeepSeek Harness](https://www.deepseek.com/en/harness/)
- Captured: 2026-11-16 through Firecrawl; HTTP 200; English page; 1440 × 900 viewport; first viewport only.
- The page text shows a compact header with the brand, language control, a desktop download menu, and links to GitHub, developer docs, community plugins, and the paper. The download menu exposes macOS and Windows packages.
- Firecrawl branding extraction reports DM Sans for body text, Montserrat for headings, white background, near-black text, and a green link/action accent. It reports a pill-style primary button with no shadow.
- A screenshot URL was returned, but pixel inspection was unavailable in this environment: the browser surface failed to initialize and the screenshot host rejected a direct fetch. Component spacing and header proportions here are implementation estimates, not measured CSS values.

## Implementation choices

- Keep nuvx's existing Bricolage Grotesque display face and system body stack.
- Use a black, white, and neutral-gray palette, as requested. DeepSeek's green accent is intentionally omitted.
- Use a compact full-width sticky header: nuvx mark, four in-page links, a working download popover, a monochrome theme switch, and a collapsible mobile menu.
- Keep downloads one click away for Android, Windows, macOS, Linux, and iOS. Keep the theme switch functional in grayscale.
- Add a top-listener panel that renders at most ten opted-in listeners from the bot feed. It shows an empty state until the feed is fresh; it does not invent member names.

## Starred design plugin

The signed-in GitHub account is `lylicht`. I used its starred [web-designer-plugin](https://github.com/MickeyAlton33/web-designer-plugin), including its [`web-designer` skill](https://github.com/MickeyAlton33/web-designer-plugin/blob/main/skills/web-designer/SKILL.md), as the design source. Its restraint, typography-led hierarchy, palette constraint, and low-shadow treatment guided the implementation; the monochrome palette follows your black-and-white request. The plugin is accessed through GitHub here rather than installed into the Codex runtime, and adds no website dependency.

## Unresolved details

- The `/api/community` endpoint returns an empty snapshot until the nuvx Discord bot is configured and publishing opt-in voice-listening totals.
- The browser capture could not be inspected at the pixel level, so the implementation follows the extracted header content and branding data rather than claiming a pixel-perfect match.
