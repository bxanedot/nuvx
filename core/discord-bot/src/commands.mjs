import { SlashCommandBuilder } from 'discord.js';

export const commands = [
  new SlashCommandBuilder()
    .setName('play')
    .setDescription('Play a YouTube Music song in your voice channel')
    .addStringOption((option) => option
      .setName('query')
      .setDescription('A YouTube Music link or song search')
      .setMaxLength(200)
      .setRequired(true)),
  new SlashCommandBuilder()
    .setName('stop')
    .setDescription('Stop nuvx playback and leave the voice channel'),
  new SlashCommandBuilder()
    .setName('listeners')
    .setDescription('Count server members and current voice listeners'),
  new SlashCommandBuilder()
    .setName('top-listeners')
    .setDescription('Show the ten most active opted-in nuvx listeners from the last 30 days'),
  new SlashCommandBuilder()
    .setName('leaderboard')
    .setDescription('Choose whether your listening time appears on the public leaderboard')
    .addStringOption((option) => option
      .setName('action')
      .setDescription('Opt in or remove your listening history')
      .setRequired(true)
      .addChoices(
        { name: 'Opt in', value: 'opt-in' },
        { name: 'Opt out and delete my saved total', value: 'opt-out' },
      )),
].map((command) => command.toJSON());
