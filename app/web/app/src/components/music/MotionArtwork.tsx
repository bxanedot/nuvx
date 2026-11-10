import { useEffect, useRef, useState } from 'react';
import type { AppleMotion } from '@/data/apple/motionArt';

const nativeHls = () => document.createElement('video').canPlayType('application/vnd.apple.mpegurl') !== '';

export function MotionArtwork({ motion, className, playing = true }: { motion: AppleMotion; className?: string; playing?: boolean }) {
  const video = useRef<HTMLVideoElement>(null);
  const [url, setUrl] = useState(motion.url);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    setUrl(motion.url);
    setReady(false);
  }, [motion.url]);

  useEffect(() => {
    const el = video.current;
    if (!el) return;
    const retry = () => {
      if (motion.fallbackUrl && url !== motion.fallbackUrl) setUrl(motion.fallbackUrl);
    };
    let destroyed = false;
    let hls: import('hls.js').default | null = null;
    void import('hls.js').then(({ default: Hls }) => {
      if (destroyed) return;
      if (!Hls.isSupported()) {
        if (!nativeHls()) return;
        el.src = url;
        el.addEventListener('error', retry, { once: true });
        return;
      }
      hls = new Hls({ capLevelToPlayerSize: true, maxBufferLength: 10, enableWorker: true });
      hls.on(Hls.Events.ERROR, (_, data) => {
        if (data.fatal) retry();
      });
      hls.loadSource(url);
      hls.attachMedia(el);
    });
    return () => {
      destroyed = true;
      hls?.destroy();
      el.removeEventListener('error', retry);
      if (el.getAttribute('src')) {
        el.removeAttribute('src');
        el.load();
      }
    };
  }, [url, motion.fallbackUrl]);

  useEffect(() => {
    const el = video.current;
    if (!el) return;
    if (playing) void el.play().catch(() => undefined);
    else el.pause();
  }, [playing, url]);

  return (
    <video
      ref={video}
      className={className}
      data-ready={ready || undefined}
      muted
      loop
      playsInline
      autoPlay={playing}
      disablePictureInPicture
      aria-hidden="true"
      onPlaying={() => setReady(true)}
    />
  );
}
