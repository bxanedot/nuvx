import { useMutation, useQuery } from '@tanstack/react-query';
import { useEffect } from 'react';
import type { DetectedExtension, YtAuthState } from '@nuvx/shared/bridge';
import { queryClient } from '@/lib/queryClient';
import { extensionQueryKey, useExtension } from './useExtension';

export const ytAuthQueryKey = ['yt', 'auth'] as const;

const SIGNED_OUT: YtAuthState = { browserSignedIn: false, connected: true, signedIn: false, accounts: [], active: null, problem: null };

function detected(): DetectedExtension | null {
  return queryClient.getQueryData<DetectedExtension | null>(extensionQueryKey) ?? null;
}

function supportsAuth(extension: DetectedExtension | null): boolean {
  return !!extension?.info.capabilities.includes('yt.auth');
}

export function ytAuthSnapshot(): YtAuthState {
  return queryClient.getQueryData<YtAuthState>(ytAuthQueryKey) ?? SIGNED_OUT;
}

export function identityOf(state: YtAuthState): string {
  return state.signedIn && state.active ? state.active.id : 'anonymous';
}

let lastIdentity: string | null = null;
let musicIdentity: string | null = null;

export function noteMusicIdentity(identity: string): void {
  musicIdentity ??= identity;
  if (musicIdentity !== identity) resetMusic();
  musicIdentity = identity;
}

function resetMusic(): void {
  musicIdentity = null;
  void queryClient.cancelQueries({ queryKey: ['music'] }).then(() => queryClient.resetQueries({ queryKey: ['music'] }));
}

function adopt(state: YtAuthState): void {
  queryClient.setQueryData(ytAuthQueryKey, state);
  const identity = identityOf(state);
  if (lastIdentity !== null && identity !== lastIdentity) {
    void import('@/playback/youtube/resolver').then((m) => m.onSessionChanged());
  }
  if (musicIdentity !== null && identity !== musicIdentity) resetMusic();
  lastIdentity = identity;
}

async function readAuth(refresh: boolean): Promise<YtAuthState> {
  const extension = detected();
  if (!extension || !supportsAuth(extension)) return SIGNED_OUT;
  const state = await extension.bridge.call('yt.authState', { refresh }, { timeoutMs: 20_000 });
  adopt(state);
  return state;
}

export async function ensureYtAuth(): Promise<YtAuthState> {
  const cached = queryClient.getQueryData<YtAuthState>(ytAuthQueryKey);
  if (cached) return cached;
  return queryClient.fetchQuery({ queryKey: ytAuthQueryKey, queryFn: () => readAuth(false), staleTime: 30_000 });
}

export type YtAuthStatus = { state: 'checking' } | { state: 'unavailable' } | { state: 'ready'; auth: YtAuthState };

export function useYtAuth(): YtAuthStatus {
  const extension = useExtension();
  const present = extension.state === 'present' ? extension.extension : null;
  const enabled = supportsAuth(present);
  const query = useQuery({
    queryKey: ytAuthQueryKey,
    queryFn: () => readAuth(false),
    enabled,
    staleTime: 30_000,
    refetchOnWindowFocus: true,
    retry: 1,
  });

  useEffect(() => {
    if (!present || !enabled) return;
    return present.bridge.subscribe((event) => {
      if (event.event === 'yt.authState') adopt(event.data);
    });
  }, [present, enabled]);

  if (extension.state === 'checking' || (enabled && query.isPending)) return { state: 'checking' };
  if (!enabled) return { state: 'unavailable' };
  return { state: 'ready', auth: query.data ?? SIGNED_OUT };
}

export function useSelectChannel() {
  return useMutation({
    mutationFn: async (channelId: string) => {
      const extension = detected();
      if (!extension) throw new Error('The extension is not installed');
      return extension.bridge.call('yt.selectChannel', { channelId });
    },
    onSuccess: adopt,
  });
}

export function useRefreshYtAuth() {
  return useMutation({ mutationFn: () => readAuth(true) });
}

export function useSetConnected() {
  return useMutation({
    mutationFn: async (connected: boolean) => {
      const extension = detected();
      if (!extension) throw new Error('The extension is not installed');
      return extension.bridge.call('yt.setConnected', { connected });
    },
    onSuccess: adopt,
  });
}
