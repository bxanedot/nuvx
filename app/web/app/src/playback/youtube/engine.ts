import { SabrStreamingAdapter } from 'googlevideo/sabr-streaming-adapter';
import { buildSabrFormat } from 'googlevideo/utils';
import shaka from 'shaka-player/dist/shaka-player.compiled';
import { Constants, Utils, YT, type Innertube } from 'youtubei.js/web';
import { timed, trackLog } from '../trackLog';
import { botguard } from './botguard';
import { ytAuthSnapshot } from '@/extension/useYtAuth';
import { getInnertube, getSignedInnertube } from './innertube';
import {
  CLIENT_ATTEMPTS,
  usesWebPo,
  excludeClient,
  playerRequest,
  resolvePlayer,
  UnplayableError,
  type PlayerClientName,
  type ResolvedPlayer,
} from './resolver';
import { ShakaPlayerAdapter } from './ShakaPlayerAdapter';

export interface LoadedTrack {
  videoId: string;
  title: string;
  author: string;
  durationSec: number;
  thumbnails: { url: string; width: number; height: number }[];
  client: PlayerClientName;
  format: string;
  stream: StreamStats;
  loudnessDb: number | null;
}

export interface StreamStats {
  codec: string | null;
  itag: string | null;
  bitrateKbps: number | null;
  sampleRateHz: number | null;
  channels: number | null;
  mode: 'direct' | 'SABR';
}

export interface VideoStream {
  videoId: string;
  manifest: string;
  aspect: number;
  heights: number[];
}

const MIN_VIDEO_ASPECT = 1.2;

export class SupersededError extends Error {
  override readonly name = 'SupersededError';
}

const RETRY_STREAMING_SECONDS = 2;

const FIRST_AUDIO_TIMEOUT_MS = 15_000;

export class YouTubeEngine {
  readonly audio: HTMLAudioElement;
  private player: shaka.Player | null = null;
  private adapter: SabrStreamingAdapter | null = null;
  private yt: Innertube | null = null;
  private generation = 0;
  private loading = false;
  private current: { videoId: string; client: PlayerClientName; signed: boolean } | null = null;
  private direct: ResolvedPlayer | null = null;
  private preferredCodecs: string[];

  onFatal: (videoId: string, message: string, permanent: boolean) => void = () => {};
  onRecovered: (track: LoadedTrack) => void = () => {};

  constructor(audio: HTMLAudioElement) {
    this.audio = audio;
    const opus = typeof MediaSource !== 'undefined' && MediaSource.isTypeSupported('audio/webm; codecs="opus"');
    this.preferredCodecs = opus ? ['opus', 'mp4a'] : ['mp4a'];
  }

  private async ensurePlayer(): Promise<shaka.Player> {
    if (this.player) return this.player;
    shaka.polyfill.installAll();
    if (!shaka.Player.isBrowserSupported()) throw new Error('This browser can’t play YouTube streams (no Media Source Extensions)');
    const player = new shaka.Player();
    player.configure({
      manifest: { disableVideo: true, disableText: true, disableThumbnails: true },
      abr: { enabled: false },
      preferredAudioCodecs: this.preferredCodecs,
      streaming: {
        bufferingGoal: 120,
        rebufferingGoal: 0.01,
        bufferBehind: 300,
        retryParameters: { maxAttempts: 1, baseDelay: 1000, backoffFactor: 1, fuzzFactor: 0.5, timeout: 30_000 },
        failureCallback: (error: shaka.util.Error) => {
          if (!isRefusal(error)) player.retryStreaming(RETRY_STREAMING_SECONDS);
        },
      },
    });
    await player.attach(this.audio);
    player.addEventListener('error', (event) => this.onShakaError((event as unknown as { detail: shaka.util.Error }).detail));
    this.player = player;
    return player;
  }

  async warm(): Promise<void> {
    this.yt ??= await getInnertube();
    await botguard.init().catch((error: unknown) => trackLog.w(`BotGuard warm-up failed: ${String(error)}`));
  }

  async load(videoId: string, { startSec = 0, autoplay = true } = {}): Promise<LoadedTrack> {
    const generation = ++this.generation;
    this.loading = true;
    const started = performance.now();
    trackLog.i(`loading at ${startSec.toFixed(1)}s`, videoId);
    try {
      const player = await this.ensurePlayer();
      this.yt ??= await timed('InnerTube session', videoId, getInnertube);
      void botguard.init().catch((error: unknown) => trackLog.w(`BotGuard init failed: ${String(error)}`, videoId));

      const skip = new Set<PlayerClientName>();
      let lastError: unknown = null;
      let yt = this.yt;
      let signed = false;
      for (let attempt = 0; attempt < CLIENT_ATTEMPTS; attempt++) {
        let resolved: ResolvedPlayer;
        try {
          resolved = await timed('player request', videoId, () => resolvePlayer(yt, videoId, skip, signed));
        } catch (error) {
          if (!(error instanceof UnplayableError && error.ageGated) || signed || !ytAuthSnapshot().signedIn) throw error;
          trackLog.i('age-restricted; asking again signed in', videoId);
          yt = await timed('signed-in InnerTube session', videoId, getSignedInnertube);
          signed = true;
          skip.clear();
          attempt = -1;
          continue;
        }
        this.checkCurrent(generation);
        try {
          const track = await this.startStream(player, resolved, startSec, autoplay, generation);
          trackLog.i(
            `playing via ${track.client}, ${track.format}, first audio after ${Math.round(performance.now() - started)}ms`,
            videoId,
          );
          return track;
        } catch (error) {
          if (error instanceof SupersededError) throw error;
          lastError = error;
          skip.add(resolved.client);
          excludeClient(videoId, resolved.client, `streaming failed: ${describe(error)}`, resolved.signed);
        }
      }
      throw lastError instanceof Error ? lastError : new Error('No YouTube client could stream this track');
    } catch (error) {
      if (!(error instanceof SupersededError)) {
        trackLog.e(`load failed: ${describe(error)}`, videoId);
      }
      throw error;
    } finally {
      if (generation === this.generation) this.loading = false;
    }
  }

  private checkCurrent(generation: number) {
    if (generation !== this.generation) throw new SupersededError('Replaced by a newer load');
  }

  private async startStream(
    player: shaka.Player,
    resolved: ResolvedPlayer,
    startSec: number,
    autoplay: boolean,
    generation: number,
  ): Promise<LoadedTrack> {
    const { videoId, client, info, signed } = resolved;
    await player.unload();
    this.adapter?.dispose();
    this.adapter = null;
    this.current = { videoId, client, signed };
    this.direct = null;

    const direct = hasDirectAudio(info);
    const manifest = direct ? await this.directManifest(resolved) : await this.sabrManifest(player, resolved);
    this.checkCurrent(generation);
    const mode = direct ? 'direct' : 'SABR';

    await timed(`${client} ${mode} load`, videoId, () =>
      player.load(`data:application/dash+xml;base64,${btoa(manifest)}`, startSec, 'application/dash+xml'),
    );
    this.checkCurrent(generation);
    const selected = this.selectAudio(player);
    const format = `${selected.label} ${mode}`;
    if (direct) this.direct = resolved;

    if (autoplay) {
      await this.audio.play().catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'NotAllowedError') {
          trackLog.w('autoplay was blocked by the browser; waiting for a tap', videoId);
        } else throw error;
      });
    }
    await this.firstAudio(generation);

    const basic = info.basic_info;
    return {
      videoId,
      title: basic.title ?? '',
      author: basic.author ?? '',
      durationSec: basic.duration ?? Math.round(player.seekRange().end),
      thumbnails: [...(basic.thumbnail ?? [])]
        .map((t) => ({ url: t.url, width: t.width, height: t.height }))
        .sort((a, b) => b.width - a.width),
      client,
      format,
      stream: { ...selected.stats, mode },
      loudnessDb: info.player_config?.audio_config?.loudness_db ?? null,
    };
  }

  private async directManifest({ videoId, client, info }: ResolvedPlayer): Promise<string> {
    for (const scheme of ['http', 'https']) {
      shaka.net.NetworkingEngine.registerScheme(
        scheme,
        shaka.net.HttpFetchPlugin.parse,
        shaka.net.NetworkingEngine.PluginPriority.FALLBACK,
      );
    }
    const pot = usesWebPo(client) ? await botguard.mint(videoId) : undefined;
    return info.toDash({
      format_filter: (format) => format.has_video || !format.has_audio,
      url_transformer: (url) => {
        if (pot) url.searchParams.set('pot', pot);
        return url;
      },
      manifest_options: { include_thumbnails: false },
    });
  }

  async videoStream(height: number | null = null): Promise<VideoStream | null> {
    const resolved = this.direct;
    if (!resolved) return null;
    const { videoId, client, info } = resolved;
    const pictures = (info.streaming_data?.adaptive_formats ?? []).filter(
      (f) => f.has_video && !f.has_audio && f.width && f.height && (f.url || f.signature_cipher || f.cipher),
    );
    const largest = pictures.sort((a, b) => b.width! * b.height! - a.width! * a.height!)[0];
    if (!largest || largest.width! / largest.height! < MIN_VIDEO_ASPECT) return null;
    const heights = [...new Set(pictures.map((f) => f.height!))].sort((a, b) => b - a);
    const wanted = height !== null && heights.includes(height) ? height : null;
    const pot = usesWebPo(client) ? await botguard.mint(videoId) : undefined;
    const manifest = await info.toDash({
      format_filter: (format) => !format.has_video || format.has_audio || (wanted !== null && format.height !== wanted),
      url_transformer: (url) => {
        if (pot) url.searchParams.set('pot', pot);
        return url;
      },
      manifest_options: { include_thumbnails: false },
    });
    return { videoId, manifest, aspect: largest.width! / largest.height!, heights };
  }

  private async sabrManifest(player: shaka.Player, { videoId, client, info, yt }: ResolvedPlayer): Promise<string> {
    const streaming = info.streaming_data!;
    const sessionClient = yt.session.context.client;
    const adapter = new SabrStreamingAdapter({
      playerAdapter: new ShakaPlayerAdapter(),
      clientInfo: {
        osName: sessionClient.osName,
        osVersion: sessionClient.osVersion,
        clientName: parseInt(Constants.CLIENT_NAME_IDS[sessionClient.clientName as keyof typeof Constants.CLIENT_NAME_IDS]),
        clientVersion: sessionClient.clientVersion,
      },
    });

    const coldStart = await botguard.coldStartToken(videoId);
    let webPoToken: string | undefined;
    let minting = false;
    adapter.onMintPoToken(async () => {
      if (!webPoToken && !minting) {
        minting = true;
        void botguard.mint(videoId).then((token) => {
          webPoToken = token;
          minting = false;
        });
      }
      return webPoToken ?? coldStart;
    });

    adapter.onReloadPlayerResponse(async (reloadContext) => {
      trackLog.d(`SABR asked for a fresh player response (${client})`, videoId);
      const response = await playerRequest(yt, videoId, client, reloadContext);
      const reloaded = new YT.VideoInfo([response], yt.actions, Utils.generateRandomString(16));
      adapter.setStreamingURL(await yt.session.player!.decipher(reloaded.streaming_data?.server_abr_streaming_url));
      adapter.setUstreamerConfig(
        (reloaded.player_config ?? info.player_config)?.media_common_config.media_ustreamer_request_config
          ?.video_playback_ustreamer_config,
      );
    });

    adapter.attach(player);
    this.adapter = adapter;

    adapter.setStreamingURL(await yt.session.player!.decipher(streaming.server_abr_streaming_url));
    adapter.setUstreamerConfig(info.player_config?.media_common_config.media_ustreamer_request_config?.video_playback_ustreamer_config);
    adapter.setServerAbrFormats(streaming.adaptive_formats.map(buildSabrFormat));

    return info.toDash({
      manifest_options: { is_sabr: true, captions_format: 'vtt', include_thumbnails: false },
    });
  }

  private selectAudio(player: shaka.Player): { label: string; stats: Omit<StreamStats, 'mode'> } {
    const tracks = player.getVariantTracks().filter((t) => !/drc/i.test(t.originalAudioId ?? ''));
    const statsOf = (t: shaka.extern.Track) => ({
      codec: codecName(t.audioCodec),
      itag: (t.originalAudioId ?? '').split('-')[0] || null,
      bitrateKbps: Math.round((t.audioBandwidth ?? t.bandwidth) / 1000) || null,
      sampleRateHz: t.audioSamplingRate,
      channels: t.channelsCount,
    });
    for (const codec of this.preferredCodecs) {
      const best = tracks
        .filter((t) => t.audioCodec?.startsWith(codec))
        .sort((a, b) => (b.audioBandwidth ?? b.bandwidth) - (a.audioBandwidth ?? a.bandwidth))[0];
      if (best) {
        if (!best.active) player.selectVariantTrack(best, true);
        const itag = (best.originalAudioId ?? '').split('-')[0];
        return { label: `${itag} ${codec} ${Math.round((best.audioBandwidth ?? best.bandwidth) / 1000)}kbps`, stats: statsOf(best) };
      }
    }
    const active = player.getVariantTracks().find((t) => t.active);
    return active
      ? { label: `${active.originalAudioId} ${active.audioCodec}`, stats: statsOf(active) }
      : { label: 'unknown', stats: { codec: null, itag: null, bitrateKbps: null, sampleRateHz: null, channels: null } };
  }

  private firstAudio(generation: number): Promise<void> {
    const audio = this.audio;
    if (audio.readyState >= HTMLMediaElement.HAVE_FUTURE_DATA) return Promise.resolve();
    return new Promise((resolve, reject) => {
      const done = () => {
        clearTimeout(timer);
        audio.removeEventListener('canplay', done);
        if (generation !== this.generation) reject(new SupersededError('Replaced by a newer load'));
        else resolve();
      };
      const timer = setTimeout(() => {
        audio.removeEventListener('canplay', done);
        reject(new Error(`no audio within ${FIRST_AUDIO_TIMEOUT_MS / 1000}s`));
      }, FIRST_AUDIO_TIMEOUT_MS);
      audio.addEventListener('canplay', done);
    });
  }

  private onShakaError(error: shaka.util.Error) {
    const current = this.current;
    const line = `Shaka error ${error.code} (severity ${error.severity}): ${describe(error)}`;
    if (!isRefusal(error)) {
      trackLog.d(line, current?.videoId ?? null);
      return;
    }
    trackLog.w(line, current?.videoId ?? null);
    if (this.loading || !current || error.severity !== shaka.util.Error.Severity.CRITICAL) return;
    this.current = null;
    excludeClient(current.videoId, current.client, `stream error ${error.code}`, current.signed);
    const resumeAt = this.audio.currentTime;
    const wasPlaying = !this.audio.paused;
    this.load(current.videoId, { startSec: resumeAt, autoplay: wasPlaying })
      .then((track) => this.onRecovered(track))
      .catch((failure: unknown) => {
        if (failure instanceof SupersededError) return;
        void this.stop();
        this.onFatal(current.videoId, describe(failure), failure instanceof UnplayableError);
      });
  }

  async stop(): Promise<void> {
    this.generation++;
    this.current = null;
    this.direct = null;
    await this.player?.unload();
    this.adapter?.dispose();
    this.adapter = null;
  }
}

function codecName(codec: string | null): string | null {
  if (!codec) return null;
  if (codec.startsWith('opus')) return 'Opus';
  if (codec.startsWith('mp4a')) return 'AAC';
  if (codec.startsWith('ec-3')) return 'E-AC-3';
  if (codec.startsWith('ac-3')) return 'AC-3';
  return codec.toUpperCase();
}

function hasDirectAudio(info: YT.VideoInfo): boolean {
  return !!info.streaming_data?.adaptive_formats.some((f) => f.has_audio && !f.has_video && (f.url || f.signature_cipher || f.cipher));
}

function isRefusal(error: shaka.util.Error): boolean {
  const status = error.data?.[1];
  if (error.code === shaka.util.Error.Code.BAD_HTTP_STATUS && (status === 401 || status === 403)) return true;
  return error.code === shaka.util.Error.Code.HTTP_ERROR && error.data?.[0] === 'Server streaming error';
}

function describe(error: unknown): string {
  if (error instanceof shaka.util.Error) {
    const detail = error.data?.[1];
    const status = typeof detail === 'number' ? ` HTTP ${detail}` : detail instanceof Error ? ` (${detail.message})` : '';
    const uri = typeof error.data?.[0] === 'string' ? ` ${error.data[0].slice(0, 80)}` : '';
    return `Shaka ${error.code}${status}${uri}`;
  }
  return error instanceof Error ? error.message : String(error);
}
