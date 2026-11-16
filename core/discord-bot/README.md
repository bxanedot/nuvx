# nuvx Discord bot

The bot counts server members and voice listeners, plays YouTube Music links or search results in Discord voice, and publishes an opt-in top-ten listening leaderboard to the nuvx website.

## What it tracks

- `/listeners` reports total server members, people in voice, and people listening in the bot's voice channel.
- Listening time accumulates only while the nuvx stream is playing and only for members who opted in with `/leaderboard action:opt-in`. The ranking shows their listening time from the past 30 days.
- `/leaderboard action:opt-out` deletes that member's saved total. The public feed contains display names and minutes for at most ten opted-in listeners; it never includes Discord IDs.
- The site keeps its ranking empty until a fresh bot snapshot is available.

## Music playback

Use `/play query:` with a YouTube Music link or song search. The bot resolves one track with `yt-dlp`, then streams it to the voice channel through FFmpeg and Discord voice. Install current versions of `yt-dlp` and FFmpeg on the bot host; set `YT_DLP_PATH` only if the executable is not on `PATH`.

## Setup

1. Create a Discord application and bot. Set the bot's display name to **nuvx** in the Discord Developer Portal.
2. Enable the **Server Members Intent** and add the bot to the nuvx server with `bot` and `applications.commands` scopes. Grant View Channels, Connect, and Speak permissions.
3. Copy `.env.example` to `.env` and set `DISCORD_TOKEN`, `DISCORD_APPLICATION_ID`, and `DISCORD_GUILD_ID`.
4. Install Node.js 20+, FFmpeg, and `yt-dlp`, then run `npm install`.
5. Register server commands with `npm run register`, then start the bot with `npm start`.
6. Generate a long random `BOT_STATS_TOKEN`. Set it in the bot `.env` and in the Cloudflare Worker with `wrangler secret put BOT_STATS_TOKEN`, then deploy the Worker.

The bot stores opt-in totals in `data/listeners.json`. Keep `.env` and `data/` private. Website publishing requires the Worker secret; without it, local bot commands still work and the website remains empty.
