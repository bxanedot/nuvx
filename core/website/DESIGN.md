# nuvx website design notes

## Reference and capture

- Reference: [DeepSeek Harness](https://www.deepseek.com/en/harness/)
- Captured: 2026-11-16 through Firecrawl; HTTP 200; English page; 1440 × 900 viewport; first viewport only.
- The page text shows a compact header with the brand, language control, a desktop download menu, and links to GitHub, developer docs, community plugins, and the paper. The download menu exposes macOS and Windows packages.
- Firecrawl branding extraction reports DM Sans for body text, Montserrat for headings, white background, near-black text, and a green link/action accent. It reports a pill-style primary button with no shadow.
- I visually inspected the 1440 × 900 capture. It shows a quiet brand at upper left, a language switch at upper right, a centered hero, and one pill-shaped primary action. The capture is a static top-of-page view; the raised header-on-scroll state is implemented from your requested behavior rather than attributed to the reference.

## Implementation choices

- Keep nuvx's existing Bricolage Grotesque display face and system body stack.
- Use a black, white, and neutral-gray palette, as requested. DeepSeek's green accent is intentionally omitted.
- Use a compact full-width header integrated with the page surface at scroll position zero. After a small scroll, add a translucent blurred surface, border, and restrained shadow so the sticky bar visibly comes forward.
- Keep the brand, concise links, working download popover, monochrome theme switch, and collapsible mobile menu consistent across the landing page and supporting pages.
- Center the first-screen hero around the nuvx name, with a clear download action, short product copy, and the approved thin flowing licht signature as a monochrome image credit. The signature flips from black to white with the page theme.
- Keep downloads one click away for Android, Windows, macOS, Linux, and iOS. Keep the theme switch functional in grayscale.
- Add a top-listener panel that renders at most ten opted-in listeners from the bot feed. It shows an empty state until the feed is fresh; it does not invent member names.

## Starred design plugin

The signed-in GitHub account is `lylicht`. I used its starred [web-designer-plugin](https://github.com/MickeyAlton33/web-designer-plugin), including its [`web-designer` skill](https://github.com/MickeyAlton33/web-designer-plugin/blob/main/skills/web-designer/SKILL.md), as the design source. Its restraint, typography-led hierarchy, palette constraint, and low-shadow treatment guided the implementation; the monochrome palette follows your black-and-white request. The plugin is accessed through GitHub here rather than installed into the Codex runtime, and adds no website dependency.

## Unresolved details

- The `/api/community` endpoint returns an empty snapshot until the nuvx Discord bot is configured and publishing opt-in voice-listening totals.
- The reference capture guided the hierarchy and spacing; the black-and-white palette, signature, and scroll-forward header are tailored to your brief rather than a pixel-for-pixel clone.
