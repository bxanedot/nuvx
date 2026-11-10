import { detectExtension, type DetectedExtension } from '@nuvx/shared/bridge';
import { useQuery } from '@tanstack/react-query';
import { config } from '@/config';
import { queryClient } from '@/lib/queryClient';

export type ExtensionStatus =
  | { state: 'checking' }
  | { state: 'missing' }
  | { state: 'present'; extension: DetectedExtension };

export const extensionQueryKey = ['extension', 'detect'] as const;

const RECHECKS = 4;
const RECHECK_MS = 1500;
const SLOW_RECHECK_MS = 5000;

async function detect(): Promise<DetectedExtension | null> {
  const known = queryClient.getQueryData<DetectedExtension | null>(extensionQueryKey) ?? null;
  const found = await detectExtension({ chromiumExtensionId: config.chromiumExtensionId });
  if (found || !known) return found;
  return (await detectExtension({ chromiumExtensionId: config.chromiumExtensionId, timeoutMs: 4000 })) ?? known;
}

export function useExtension(): ExtensionStatus {
  const query = useQuery({
    queryKey: extensionQueryKey,
    queryFn: detect,
    staleTime: 30_000,
    gcTime: Infinity,
    retry: false,
    refetchOnWindowFocus: true,
    refetchInterval: (q) =>
      q.state.data === null ? (q.state.dataUpdateCount <= RECHECKS ? RECHECK_MS : SLOW_RECHECK_MS) : false,
  });
  if (query.isPending) return { state: 'checking' };
  if (!query.data) return { state: 'missing' };
  return { state: 'present', extension: query.data };
}
