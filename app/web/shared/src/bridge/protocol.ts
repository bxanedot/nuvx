export const BRIDGE_PROTOCOL = 1;

export const BRIDGE_CHANNEL = 'nuvx-bridge';

export const EVENTS_PORT = 'nuvx-events';

export type Capability = 'yt' | 'yt.auth' | 'yt.stats' | 'addon' | 'lyrics' | 'spotify' | 'discord';

export type BrowserFamily = 'chromium' | 'firefox';

export interface HttpRequest {
  url: string;
  method?: 'GET' | 'POST' | 'HEAD';
  headers?: Record<string, string>;
  body?: string;
  bodyEncoding?: 'text' | 'base64';
}

export interface HttpResponse {
  status: number;
  headers: Record<string, string>;
  body: string;
  bodyEncoding: 'text' | 'base64';
  url: string;
}

export interface YtChannel {
  id: string;
  name: string;
  handle: string | null;
  avatarUrl: string | null;
  brand: boolean;
}

export interface YtAccount {
  authUser: string;
  name: string;
  email: string | null;
  avatarUrl: string | null;
  channels: YtChannel[];
}

export interface YtAuthState {
  browserSignedIn: boolean;
  connected: boolean;
  signedIn: boolean;
  accounts: YtAccount[];
  active: (YtChannel & { authUser: string }) | null;
  problem: string | null;
}

export interface SpotifyAuthState {
  browserSignedIn: boolean;
  connected: boolean;
  signedIn: boolean;
  user: { name: string; avatarUrl: string | null } | null;
  problem: string | null;
}

export interface SpotifyPlaylist {
  id: string;
  name: string;
  owner: string | null;
  imageUrl: string | null;
}

export interface SpotifyTrack {
  id: string;
  title: string;
  artist: string;
  album: string | null;
  durationMs: number;
  imageUrl: string | null;
}

export interface SpotifyPage<T> {
  items: T[];
  total: number;
  raw: number;
}

export interface DiscordState {
  available: boolean;
  connected: boolean;
  enabled: boolean;
  user: { name: string; username: string; avatarUrl: string | null } | null;
  gateway: 'off' | 'connecting' | 'ready' | 'error';
  problem: string | null;
}

export interface DiscordPresenceOptions {
  activityType: 'listening' | 'playing' | 'watching' | 'competing';
  status: 'online' | 'idle' | 'dnd' | 'invisible';
  useDetails: boolean;
  activityName: string;
  button1Text: string;
  button1Visible: boolean;
  button2Text: string;
  button2Visible: boolean;
}

export interface DiscordNowPlaying {
  videoId: string;
  title: string;
  artist: string;
  album: string | null;
  artworkUrl: string | null;
  positionMs: number;
  durationMs: number;
  playbackRate: number;
}

export type BridgeEvent =
  | { event: 'yt.authState'; data: YtAuthState }
  | { event: 'spotify.authState'; data: SpotifyAuthState }
  | { event: 'discord.state'; data: DiscordState };

export interface YtFetchParams extends HttpRequest {
  auth?: boolean;
}

export interface BridgeMethods {
  ping: {
    params: Record<string, never>;
    result: {
      version: string;
      protocol: number;
      browser: BrowserFamily;
      capabilities: Capability[];
    };
  };
  'yt.fetch': { params: YtFetchParams; result: HttpResponse };
  'yt.authState': { params: { refresh?: boolean }; result: YtAuthState };
  'yt.selectChannel': { params: { channelId: string }; result: YtAuthState };
  'yt.setConnected': { params: { connected: boolean }; result: YtAuthState };
  'addon.fetch': { params: HttpRequest; result: HttpResponse };
  'lyrics.fetch': { params: HttpRequest; result: HttpResponse };
  'lyrics.permissions': { params: { origins: string[] }; result: { granted: string[] } };
  'lyrics.requestPermissions': { params: { origins: string[] }; result: Record<string, never> };
  'spotify.authState': { params: { refresh?: boolean }; result: SpotifyAuthState };
  'spotify.setConnected': { params: { connected: boolean }; result: SpotifyAuthState };
  'spotify.signIn': { params: Record<string, never>; result: Record<string, never> };
  'spotify.playlists': { params: { offset: number }; result: SpotifyPage<SpotifyPlaylist> };
  'spotify.tracks': { params: { playlistId: string; offset: number }; result: SpotifyPage<SpotifyTrack> };
  'spotify.playlist': {
    params: { playlistId: string };
    result: { name: string; owner: string | null; imageUrl: string | null; total: number };
  };
  'spotify.searchTracks': { params: { query: string }; result: SpotifyTrack[] };
  'spotify.canvas': { params: { trackId: string }; result: { url: string | null } };
  'discord.state': { params: Record<string, never>; result: DiscordState };
  'discord.connect': { params: Record<string, never>; result: Record<string, never> };
  'discord.disconnect': { params: Record<string, never>; result: DiscordState };
  'discord.setEnabled': { params: { enabled: boolean }; result: DiscordState };
  'discord.presence': {
    params: { track: DiscordNowPlaying | null; options: DiscordPresenceOptions };
    result: Record<string, never>;
  };
}

export type BridgeMethod = keyof BridgeMethods;
export type ParamsOf<M extends BridgeMethod> = BridgeMethods[M]['params'];
export type ResultOf<M extends BridgeMethod> = BridgeMethods[M]['result'];

export interface BridgeRequest<M extends BridgeMethod = BridgeMethod> {
  channel: typeof BRIDGE_CHANNEL;
  protocol: number;
  direction: 'to-extension';
  id: string;
  method: M;
  params: ParamsOf<M>;
}

export type BridgeErrorCode =
  | 'unsupported'
  | 'forbidden'
  | 'protocol'
  | 'network'
  | 'unauthenticated'
  | 'permission'
  | 'internal';

export interface BridgeError {
  code: BridgeErrorCode;
  message: string;
}

export type BridgeResponse<M extends BridgeMethod = BridgeMethod> = {
  channel: typeof BRIDGE_CHANNEL;
  protocol: number;
  direction: 'to-page';
  id: string;
} & ({ ok: true; result: ResultOf<M> } | { ok: false; error: BridgeError });

export function isBridgeRequest(value: unknown): value is BridgeRequest {
  const v = value as Partial<BridgeRequest> | null;
  return (
    typeof v === 'object' &&
    v !== null &&
    v.channel === BRIDGE_CHANNEL &&
    v.direction === 'to-extension' &&
    typeof v.id === 'string' &&
    typeof v.method === 'string'
  );
}

export interface BridgeEventMessage {
  channel: typeof BRIDGE_CHANNEL;
  direction: 'event';
  payload: BridgeEvent;
}

export function isBridgeEventMessage(value: unknown): value is BridgeEventMessage {
  const v = value as Partial<BridgeEventMessage> | null;
  return typeof v === 'object' && v !== null && v.channel === BRIDGE_CHANNEL && v.direction === 'event' && !!v.payload;
}

export function isBridgeResponse(value: unknown): value is BridgeResponse {
  const v = value as Partial<BridgeResponse> | null;
  return (
    typeof v === 'object' &&
    v !== null &&
    v.channel === BRIDGE_CHANNEL &&
    v.direction === 'to-page' &&
    typeof v.id === 'string' &&
    typeof v.ok === 'boolean'
  );
}

export function okResponse<M extends BridgeMethod>(id: string, result: ResultOf<M>): BridgeResponse<M> {
  return { channel: BRIDGE_CHANNEL, protocol: BRIDGE_PROTOCOL, direction: 'to-page', id, ok: true, result };
}

export function errorResponse(id: string, code: BridgeErrorCode, message: string): BridgeResponse {
  return { channel: BRIDGE_CHANNEL, protocol: BRIDGE_PROTOCOL, direction: 'to-page', id, ok: false, error: { code, message } };
}
