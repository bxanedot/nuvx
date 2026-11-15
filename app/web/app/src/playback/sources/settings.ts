import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { SourceProvider } from '@nuvx/shared/bridge';

type State = Record<SourceProvider, boolean> & { set: (provider: SourceProvider, on: boolean) => void };

/** Non-YouTube sources are off until the user turns them on. */
export const useSourceSettings = create<State>()(
  persist(
    (set) => ({ jiosaavn: false, radiojavan: false, set: (provider, on) => set({ [provider]: on }) }),
    { name: 'nuvx:sources', version: 1, partialize: ({ jiosaavn, radiojavan }) => ({ jiosaavn, radiojavan }) },
  ),
);
