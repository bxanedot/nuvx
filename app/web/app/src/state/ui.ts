import { create } from 'zustand';
import { readStorage, writeStorage } from '@/lib/storage';

export type SidePanel = 'up-next' | 'lyrics' | null;

interface UiState {
  sidePanel: SidePanel;
  toggleSidePanel: (panel: Exclude<SidePanel, null>) => void;
  closeSidePanel: () => void;
  fullScreen: boolean;
  setFullScreen: (open: boolean) => void;
  drawerOpen: boolean;
  setDrawerOpen: (open: boolean) => void;
  backgroundMotion: boolean;
  setBackgroundMotion: (enabled: boolean) => void;
  installBannerDismissed: boolean;
  dismissInstallBanner: () => void;
  libraryView: 'grid' | 'list';
  setLibraryView: (view: 'grid' | 'list') => void;
}

const BANNER_KEY = 'nuvx:install-banner-dismissed';
const BACKGROUND_MOTION_KEY = 'nuvx:background-motion';
const LIBRARY_VIEW_KEY = 'nuvx:library-view';

export const useUi = create<UiState>()((set) => ({
  sidePanel: null,
  toggleSidePanel: (panel) => set((s) => ({ sidePanel: s.sidePanel === panel ? null : panel })),
  closeSidePanel: () => set({ sidePanel: null }),
  fullScreen: false,
  setFullScreen: (fullScreen) => set({ fullScreen }),
  drawerOpen: false,
  setDrawerOpen: (drawerOpen) => set({ drawerOpen }),
  backgroundMotion: readStorage('sessionStorage', BACKGROUND_MOTION_KEY) !== 'off',
  setBackgroundMotion: (backgroundMotion) => {
    writeStorage('sessionStorage', BACKGROUND_MOTION_KEY, backgroundMotion ? 'on' : 'off');
    set({ backgroundMotion });
  },
  installBannerDismissed: readStorage('sessionStorage', BANNER_KEY) === '1',
  dismissInstallBanner: () => {
    writeStorage('sessionStorage', BANNER_KEY, '1');
    set({ installBannerDismissed: true });
  },
  libraryView: readStorage('localStorage', LIBRARY_VIEW_KEY) === 'list' ? 'list' : 'grid',
  setLibraryView: (libraryView) => {
    writeStorage('localStorage', LIBRARY_VIEW_KEY, libraryView);
    set({ libraryView });
  },
}));

if (import.meta.env.DEV) Object.assign(window, { __bcUi: useUi });
