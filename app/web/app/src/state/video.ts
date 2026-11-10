import { create } from 'zustand';

export type VideoQuality = 'auto' | number;

interface VideoState {
  videoId: string | null;
  aspect: number;
  surfaces: number;
  heights: number[];
  quality: VideoQuality;
  activeHeight: number | null;
}

const QUALITY_KEY = 'nuvx:video-quality';

function storedQuality(): VideoQuality {
  const stored = localStorage.getItem(QUALITY_KEY);
  const height = Number(stored);
  return stored && Number.isFinite(height) && height > 0 ? height : 'auto';
}

export const useVideo = create<VideoState>()(() => ({
  videoId: null,
  aspect: 16 / 9,
  surfaces: 0,
  heights: [],
  quality: storedQuality(),
  activeHeight: null,
}));

export function setVideoQuality(quality: VideoQuality): void {
  if (quality === 'auto') localStorage.removeItem(QUALITY_KEY);
  else localStorage.setItem(QUALITY_KEY, String(quality));
  useVideo.setState({ quality });
}

export const useHasVideo = (videoId: string | null | undefined) => useVideo((s) => !!videoId && s.videoId === videoId);

export function qualityLabel(height: number): string {
  if (height >= 4320) return '8K';
  if (height >= 2160) return '4K';
  if (height >= 1440) return '2K';
  if (height >= 1080) return 'FullHD';
  if (height >= 720) return 'HD';
  return `${height}p`;
}

let element: HTMLVideoElement | null = null;

export function getVideoElement(): HTMLVideoElement {
  if (!element) {
    element = document.createElement('video');
    element.muted = true;
    element.playsInline = true;
    element.disablePictureInPicture = true;
    element.setAttribute('aria-hidden', 'true');
    if (import.meta.env.DEV) Object.assign(window, { __bcVideo: element });
  }
  return element;
}

export type VideoFit = 'cover' | 'contain';

const stack: { host: HTMLElement; fit: VideoFit }[] = [];

function place() {
  const top = stack[stack.length - 1];
  const video = getVideoElement();
  if (!top) {
    video.remove();
    return;
  }
  video.className = top.fit === 'contain' ? 'block size-full object-contain' : 'block size-full object-cover';
  if (video.parentElement !== top.host) top.host.append(video);
}

export function claimVideoSurface(host: HTMLElement, fit: VideoFit): () => void {
  const entry = { host, fit };
  stack.push(entry);
  place();
  useVideo.setState((s) => ({ surfaces: s.surfaces + 1 }));
  return () => {
    const at = stack.indexOf(entry);
    if (at >= 0) stack.splice(at, 1);
    place();
    useVideo.setState((s) => ({ surfaces: s.surfaces - 1 }));
  };
}
