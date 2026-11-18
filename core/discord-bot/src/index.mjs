import 'dotenv/config';
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import { spawn } from 'node:child_process';
import { dirname, resolve } from 'node:path';
import {
  AudioPlayerStatus,
  VoiceConnectionStatus,
  createAudioPlayer,
  createAudioResource,
  entersState,
  joinVoiceChannel,
  StreamType,
} from '@discordjs/voice';
import { Client, Events, GatewayIntentBits } from 'discord.js';

const token = process.env.DISCORD_TOKEN;
const guildId = process.env.DISCORD_GUILD_ID;
const workerUrl = (process.env.NUVX_WORKER_URL || 'https://nuvx-listen.lylicht.workers.dev').replace(/\/$/, '');
const statsToken = process.env.BOT_STATS_TOKEN || '';
const statsFile = resolve(process.env.STATS_FILE || './data/listeners.json');
const ytdlpPath = process.env.YT_DLP_PATH || 'yt-dlp';
const activeWindowMs = 30 * 24 * 60 * 60 * 1000;
const tickSeconds = 30;

if (!token || !guildId) throw new Error('Set DISCORD_TOKEN and DISCORD_GUILD_ID.');

function musicSource(value) {
  const query = String(value || '').trim();
  if (!query || query.length > 200) throw new Error('Enter a YouTube Music link or a search under 200 characters.');
  if (!/^https?:\/\//i.test(query)) return `ytsearch1:${query}`;
  const url = new URL(query);
  const allowedHosts = new Set(['music.youtube.com', 'youtube.com', 'www.youtube.com', 'm.youtube.com', 'youtu.be']);
  if (url.protocol !== 'https:' || url.username || url.password || !allowedHosts.has(url.hostname.toLowerCase())) {
    throw new Error('Use an HTTPS YouTube Music or YouTube song link.');
  }
  return url.toString();
}

function resolveTrack(source) {
  return new Promise((resolveTrack, rejectTrack) => {
    const extractor = spawn(ytdlpPath, [
      '--dump-single-json', '--no-playlist', '--no-warnings', '--quiet', '--skip-download', source,
    ], { stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
    let stdout = '';
    let stderr = '';
    const timeout = setTimeout(() => extractor.kill('SIGKILL'), 20_000);
    timeout.unref();
    extractor.stdout.setEncoding('utf8');
    extractor.stderr.setEncoding('utf8');
    extractor.stdout.on('data', (chunk) => {
      stdout += chunk;
      if (stdout.length > 2_000_000) extractor.kill('SIGKILL');
    });
    extractor.stderr.on('data', (chunk) => { stderr = (stderr + chunk).slice(-2_000); });
    extractor.once('error', (error) => {
      clearTimeout(timeout);
      rejectTrack(new Error(error.code === 'ENOENT' ? 'Install yt-dlp on the bot host first.' : error.message));
    });
    extractor.once('close', (code) => {
      clearTimeout(timeout);
      if (code !== 0) return rejectTrack(new Error(stderr.trim() || 'YouTube Music could not resolve that track.'));
      try {
        const item = JSON.parse(stdout.trim());
        const url = item.webpage_url || item.original_url;
        if (!url || !item.title) throw new Error('No matching track was found.');
        resolveTrack({ url, title: String(item.title).slice(0, 180), artist: String(item.artist || item.uploader || '').slice(0, 120) });
      } catch (error) {
        rejectTrack(error);
      }
    });
  });
}

function recentDayKey(timestamp) {
  return new Date(timestamp).toISOString().slice(0, 10);
}

function cleanListeningDays(value, now = Date.now()) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {};
  const cutoff = recentDayKey(now - activeWindowMs);
  return Object.fromEntries(Object.entries(value).flatMap(([day, seconds]) => {
    const amount = Math.floor(Number(seconds));
    return /^\d{4}-\d{2}-\d{2}$/.test(day) && day >= cutoff && Number.isFinite(amount) && amount > 0
      ? [[day, amount]]
      : [];
  }));
}

function recentListeningSeconds(user) {
  const cutoff = recentDayKey(Date.now() - activeWindowMs);
  return Object.entries(user.listeningDays).reduce((total, [day, seconds]) =>
    day >= cutoff ? total + seconds : total, 0);
}

const users = new Map();
const sessions = new Map();
let saveChain = Promise.resolve();

async function loadStats() {
  try {
    const saved = JSON.parse(await readFile(statsFile, 'utf8'));
    for (const [id, value] of Object.entries(saved.users || {})) {
      if (!value || typeof value !== 'object') continue;
      users.set(id, {
        displayName: String(value.displayName || 'Listener').slice(0, 80),
        listeningDays: cleanListeningDays(value.listeningDays),
        lastActiveAt: Math.max(0, Number(value.lastActiveAt) || 0),
        optedIn: value.optedIn === true,
      });
    }
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }
}

function saveStats() {
  const payload = JSON.stringify({ users: Object.fromEntries(users) }, null, 2);
  saveChain = saveChain.catch(() => {}).then(async () => {
    await mkdir(dirname(statsFile), { recursive: true });
    const temporary = `${statsFile}.tmp`;
    await writeFile(temporary, payload, { mode: 0o600 });
    await rename(temporary, statsFile);
  });
  return saveChain;
}

const client = new Client({
  intents: [GatewayIntentBits.Guilds, GatewayIntentBits.GuildVoiceStates, GatewayIntentBits.GuildMembers],
});

const safeName = (value) => String(value)
  .replace(/[\\\r\n`@*|_~]/g, (character) => character === '@' ? '@\u200b' : `\\${character}`)
  .replace(/\[/g, '\\[')
  .replace(/\]/g, '\\]');

function topListeners() {
  const cutoff = Date.now() - activeWindowMs;
  return [...users.values()]
    .map((user) => ({ user, listeningSeconds: recentListeningSeconds(user) }))
    .filter(({ user, listeningSeconds }) => user.optedIn && listeningSeconds > 0 && user.lastActiveAt >= cutoff)
    .sort((a, b) => b.listeningSeconds - a.listeningSeconds)
    .slice(0, 10)
    .map(({ user, listeningSeconds }) => ({
      displayName: user.displayName,
      minutes: Math.floor(listeningSeconds / 60),
    }));
}

function listeningMembers(session) {
  if (!session || session.player.state.status !== AudioPlayerStatus.Playing) return [];
  return [...session.channel.members.values()].filter((member) => !member.user.bot);
}

function activeVoiceCount(guild) {
  return [...guild.voiceStates.cache.values()].filter((state) =>
    Boolean(state.channelId) && state.id !== client.user.id && state.member?.user.bot !== true,
  ).length;
}

function stopSession(serverId) {
  const session = sessions.get(serverId);
  if (!session) return false;
  sessions.delete(serverId);
  session.extractor?.kill('SIGTERM');
  session.player.stop(true);
  session.connection.destroy();
  return true;
}

async function startStream(interaction) {
  const channel = interaction.guild?.members.cache.get(interaction.user.id)?.voice.channel;
  if (!channel) return interaction.reply({ content: 'Join a voice channel first.', ephemeral: true });
  let source;
  try {
    source = musicSource(interaction.options.getString('query', true));
  } catch (error) {
    return interaction.reply({ content: error.message, ephemeral: true });
  }
  await interaction.deferReply({ ephemeral: true });
  stopSession(interaction.guildId);

  let connection;
  let extractor;
  try {
    const track = await resolveTrack(source);

    connection = joinVoiceChannel({
      channelId: channel.id,
      guildId: channel.guild.id,
      adapterCreator: channel.guild.voiceAdapterCreator,
      selfDeaf: true,
    });
    await entersState(connection, VoiceConnectionStatus.Ready, 15_000);

    extractor = spawn(ytdlpPath, [
      '--no-playlist', '--no-warnings', '--quiet', '--format', 'bestaudio/best', '--output', '-', track.url,
    ], { stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
    await new Promise((resolveSpawn, rejectSpawn) => {
      const onError = (error) => rejectSpawn(error);
      extractor.once('spawn', () => {
        extractor.removeListener('error', onError);
        resolveSpawn();
      });
      extractor.once('error', onError);
    });

    const player = createAudioPlayer();
    const session = { channel, connection, extractor, player };
    sessions.set(interaction.guildId, session);
    connection.subscribe(player);
    player.on('error', (error) => {
      console.error('nuvx stream playback failed:', error.message);
      stopSession(interaction.guildId);
    });
    extractor.stderr.on('data', (chunk) => console.error('yt-dlp:', String(chunk).trim().slice(-500)));
    extractor.once('error', (error) => {
      console.error('nuvx YouTube Music extraction failed:', error.message);
      stopSession(interaction.guildId);
    });
    extractor.once('close', (code) => {
      if (code !== 0 && sessions.get(interaction.guildId)?.extractor === extractor) {
        console.error(`nuvx YouTube Music stream ended with yt-dlp exit code ${code}.`);
        stopSession(interaction.guildId);
      }
    });
    player.once(AudioPlayerStatus.Idle, () => stopSession(interaction.guildId));
    player.play(createAudioResource(extractor.stdout, {
      inputType: StreamType.Arbitrary,
      metadata: track,
    }));
    await interaction.editReply(`Now playing **${track.title}**${track.artist ? ` — ${track.artist}` : ''} in **${channel.name}**.`);
  } catch (error) {
    extractor?.kill('SIGTERM');
    connection?.destroy();
    console.error('Could not start nuvx playback:', error.message);
    await interaction.editReply(`nuvx could not start that track: ${error.message}`);
  }
}

async function handleInteraction(interaction) {
  if (!interaction.isChatInputCommand()) return;
  if (interaction.guildId !== guildId) {
    return interaction.reply({ content: 'This nuvx bot is configured for one server.', ephemeral: true });
  }

  if (interaction.commandName === 'play') return startStream(interaction);
  if (interaction.commandName === 'stop') {
    const stopped = stopSession(interaction.guildId);
    return interaction.reply({ content: stopped ? 'Playback stopped.' : 'nuvx is not playing right now.', ephemeral: true });
  }
  if (interaction.commandName === 'listeners') {
    const guild = interaction.guild;
    const count = activeVoiceCount(guild);
    const playing = listeningMembers(sessions.get(interaction.guildId)).length;
    return interaction.reply({
      content: `**${guild.memberCount.toLocaleString()}** server members · **${count.toLocaleString()}** in voice · **${playing.toLocaleString()}** listening to nuvx now.`,
      ephemeral: true,
    });
  }
  if (interaction.commandName === 'top-listeners') {
    const rows = topListeners();
    const content = rows.length
      ? rows.map((row, index) => `${String(index + 1).padStart(2, '0')}  ${safeName(row.displayName)} — ${row.minutes.toLocaleString()} min`).join('\n')
      : 'No opted-in listening totals yet. Use `/leaderboard action:opt-in` while listening to nuvx.';
    return interaction.reply({ content, ephemeral: true, allowedMentions: { parse: [] } });
  }
  if (interaction.commandName === 'leaderboard') {
    const action = interaction.options.getString('action', true);
    const userId = interaction.user.id;
    if (action === 'opt-out') {
      users.delete(userId);
      await saveStats();
      return interaction.reply({ content: 'Your saved listening total was deleted and will leave the public ranking on the next update.', ephemeral: true });
    }
    const prior = users.get(userId);
    users.set(userId, {
      displayName: interaction.member?.displayName || interaction.user.globalName || interaction.user.username,
      listeningDays: cleanListeningDays(prior?.listeningDays),
      lastActiveAt: prior?.lastActiveAt || 0,
      optedIn: true,
    });
    await saveStats();
    return interaction.reply({ content: 'You are opted in. nuvx will count your time only while its stream is playing in your voice channel.', ephemeral: true });
  }
}

async function publishCommunitySnapshot() {
  const guild = client.guilds.cache.get(guildId);
  if (!guild) return;
  const session = sessions.get(guildId);
  const listeners = listeningMembers(session);
  let changed = false;
  const now = Date.now();
  for (const member of listeners) {
    const current = users.get(member.id);
    if (!current?.optedIn) continue;
    const updated = {
      ...current,
      displayName: member.displayName,
      listeningDays: cleanListeningDays(current.listeningDays, now),
      lastActiveAt: now,
    };
    const today = recentDayKey(now);
    updated.listeningDays[today] = (updated.listeningDays[today] || 0) + tickSeconds;
    users.set(member.id, updated);
    changed = true;
  }
  if (changed) await saveStats();
  if (!statsToken) return;

  const snapshot = {
    memberCount: guild.memberCount,
    activeListeners: listeners.length,
    listeners: topListeners(),
  };
  const response = await fetch(`${workerUrl}/api/community`, {
    method: 'POST',
    headers: { authorization: `Bearer ${statsToken}`, 'content-type': 'application/json' },
    body: JSON.stringify(snapshot),
    signal: AbortSignal.timeout(10_000),
  });
  if (!response.ok) console.error(`Community stats publish failed: HTTP ${response.status}.`);
}

await loadStats();
client.once(Events.ClientReady, (readyClient) => {
  const guild = readyClient.guilds.cache.get(guildId);
  if (!guild) console.error(`nuvx is not in configured guild ${guildId}.`);
  else console.log(`nuvx connected to ${guild.name} (${guild.memberCount} members).`);
  if (!statsToken) console.warn('BOT_STATS_TOKEN is unset; website rankings will remain empty.');
});
client.on(Events.InteractionCreate, (interaction) => {
  void handleInteraction(interaction).catch(async (error) => {
    console.error('nuvx interaction failed:', error);
    if (!interaction.isRepliable()) return;
    const message = 'nuvx could not complete that command. Check the bot logs.';
    if (interaction.deferred || interaction.replied) await interaction.followUp({ content: message, ephemeral: true });
    else await interaction.reply({ content: message, ephemeral: true });
  });
});

setInterval(() => {
  void publishCommunitySnapshot().catch((error) => console.error('Community stats update failed:', error.message));
}, tickSeconds * 1000).unref();

await client.login(token);
