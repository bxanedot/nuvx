export interface PartyTrack {
  videoId: string;
  title: string;
  artist: string;
  thumbnailUrl: string | null;
  durationMs: number | null;
  fromAutoplay: boolean;
}

export interface PartyMember {
  memberId: string;
  userId: string;
  displayName: string;
  avatarUrl: string | null;
  isHost: boolean;
  connected: boolean;
  joinedAtMs: number;
  lastSeenMs: number;
}

export interface PartyPlayback {
  seq: number;
  track: PartyTrack | null;
  queueSeq: number;
  queueLength: number;
  queueIndex: number;
  isPlaying: boolean;
  positionMs: number;
  anchorMs: number;
  effectivePositionMs: number;
  updatedBy: string | null;
  startedBy: string | null;
  startedByName: string | null;
  autoplayEnabled: boolean;
  updatedAtMs: number;
}

export interface PartyQueue {
  seq: number;
  index: number;
  items: PartyTrack[];
}

export interface PartySnapshot {
  code: string;
  createdAtMs: number;
  maxMembers: number;
  hostOnlyControl: boolean;
  members: PartyMember[];
  playback: PartyPlayback;
  queue: PartyQueue;
  serverMs: number;
}

export interface PartyPreview {
  code: string;
  hostName: string;
  memberCount: number;
  maxMembers: number;
  isFull: boolean;
  members: { displayName: string; avatarUrl: string | null; isHost: boolean }[];
}

export interface PartyMembership {
  code: string;
  token: string;
  you: PartyMember;
  party: PartySnapshot;
  serverMs: number;
}

export interface PartyActivity {
  action: string;
  by: string;
  atMs: number;
  detail: string;
}

export interface JoinRequest {
  userId: string;
  deviceId: string;
  displayName: string;
  avatarUrl?: string | null;
  maxMembers?: number;
  autoplayEnabled?: boolean;
}

export const EMPTY_PLAYBACK: PartyPlayback = {
  seq: 0,
  track: null,
  queueSeq: 0,
  queueLength: 0,
  queueIndex: -1,
  isPlaying: false,
  positionMs: 0,
  anchorMs: 0,
  effectivePositionMs: 0,
  updatedBy: null,
  startedBy: null,
  startedByName: null,
  autoplayEnabled: false,
  updatedAtMs: 0,
};

export const EMPTY_QUEUE: PartyQueue = { seq: 0, index: -1, items: [] };

export function playbackOf(raw: Partial<PartyPlayback> | null | undefined): PartyPlayback {
  return { ...EMPTY_PLAYBACK, ...raw, track: raw?.track ? trackOf(raw.track) : null };
}

export function queueOf(raw: Partial<PartyQueue> | null | undefined): PartyQueue {
  return { ...EMPTY_QUEUE, ...raw, items: (raw?.items ?? []).map(trackOf) };
}

export function trackOf(raw: Partial<PartyTrack>): PartyTrack {
  return {
    videoId: raw.videoId ?? '',
    title: raw.title ?? '',
    artist: raw.artist ?? '',
    thumbnailUrl: raw.thumbnailUrl ?? null,
    durationMs: raw.durationMs ?? null,
    fromAutoplay: raw.fromAutoplay ?? false,
  };
}

export function memberOf(raw: Partial<PartyMember>): PartyMember {
  return {
    memberId: raw.memberId ?? '',
    userId: raw.userId ?? '',
    displayName: raw.displayName ?? '',
    avatarUrl: raw.avatarUrl ?? null,
    isHost: raw.isHost ?? false,
    connected: raw.connected ?? false,
    joinedAtMs: raw.joinedAtMs ?? 0,
    lastSeenMs: raw.lastSeenMs ?? 0,
  };
}

export function snapshotOf(raw: Partial<PartySnapshot>): PartySnapshot {
  return {
    code: raw.code ?? '',
    createdAtMs: raw.createdAtMs ?? 0,
    maxMembers: raw.maxMembers ?? 5,
    hostOnlyControl: raw.hostOnlyControl ?? false,
    members: (raw.members ?? []).map(memberOf),
    playback: playbackOf(raw.playback),
    queue: queueOf(raw.queue),
    serverMs: raw.serverMs ?? 0,
  };
}
