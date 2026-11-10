import type { Bridge, DetectedExtension } from '@nuvx/shared/bridge';
import { queryClient } from '@/lib/queryClient';
import { extensionQueryKey } from './useExtension';

export function getBridge(): Bridge | null {
  return queryClient.getQueryData<DetectedExtension | null>(extensionQueryKey)?.bridge ?? null;
}
