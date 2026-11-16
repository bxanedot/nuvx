import 'dotenv/config';
import { REST, Routes } from 'discord.js';
import { commands } from './commands.mjs';

const token = process.env.DISCORD_TOKEN;
const applicationId = process.env.DISCORD_APPLICATION_ID;
const guildId = process.env.DISCORD_GUILD_ID;

if (!token || !applicationId || !guildId) {
  throw new Error('Set DISCORD_TOKEN, DISCORD_APPLICATION_ID, and DISCORD_GUILD_ID.');
}

const rest = new REST({ version: '10' }).setToken(token);
await rest.put(Routes.applicationGuildCommands(applicationId, guildId), { body: commands });
console.log(`Registered ${commands.length} nuvx commands for guild ${guildId}.`);
