# Discord Rich Presence

## Current status

Rich Presence is not implemented. Kizzy was reviewed for the Android media-presence UX: its controls include enable/disable, artist and album fields, timestamps, playback state, hide-on-pause, title formatting, and per-app filtering. Nuvx can use those as product requirements while keeping its playback state and UI independently implemented.

Kizzy's documented Android transport connects to Discord's Gateway, identifies with a user token, and sends presence updates. Its README warns that it uses this connection. Nuvx will not collect or store Discord user tokens or use that undocumented user-Gateway flow. The reviewed `MediaRpc.kt` also carries a separate no-copy/no-distribution notice, so no Kizzy source or assets are incorporated.

## Supported implementation path

Discord documents Rich Presence through its Social SDK. Its current release notes describe Android Rich Presence over an unauthenticated RPC transport, and its mobile support is generally available. Nuvx should use that documented SDK transport where the package supports the target, with platform adapters behind a shared Nuvx presence contract. The SDK is distributed from the Discord Developer Portal; no Social SDK package or Nuvx Discord application ID is configured in this repository, so implementation is blocked on bxane creating/configuring the application and providing its public application ID plus the SDK package for review.

The future Nuvx settings should be opt-in and persisted: enable presence, include artist, include album, show playback timestamps/state, and clear presence while paused. Only Nuvx's own current track should be shared, and turning the master switch off must clear the published activity. Do not request Android notification-listener access just to read Nuvx playback state.

## Sources

- [Kizzy repository and README](https://github.com/dead8309/Kizzy)
- [Discord Rich Presence overview](https://docs.discord.com/developers/platform/rich-presence)
- [Discord Social SDK](https://discord.com/developers/docs/social-sdk/index.html)
- [Discord Social SDK mobile support announcement](https://discord.com/blog/mobile-platform-support-arrives-in-discord-social-sdk)
- [Discord Social SDK release notes](https://discord.com/developers/docs/social-sdk/release_notes.html)
