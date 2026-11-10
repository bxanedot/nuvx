import type { BotGuardClient } from 'bgutils-js/botguard';
import type { IBotguardClientSideBgChallenge, WebPoSignalOutput } from 'bgutils-js/shared-types';
import type { WebPoMinter } from 'bgutils-js/webpo';
import { getBridge } from '@/extension/bridge';
import { timed, trackLog } from '../trackLog';

const REQUEST_KEY = 'O43z0dpjhgX20SCx4KAo';

const RENEW_MARGIN_MS = 10 * 60_000;
const DEFAULT_TTL_MS = 60 * 60_000;

class BotguardService {
  private client?: BotGuardClient;
  private challenge?: IBotguardClientSideBgChallenge;
  private minter?: WebPoMinter;
  private expiresAt = 0;
  private initializing: Promise<void> | null = null;
  private tokens = new Map<string, string>();

  init(): Promise<void> {
    if (this.minter && Date.now() < this.expiresAt - RENEW_MARGIN_MS) return Promise.resolve();
    this.initializing ??= timed('BotGuard init', null, () => this.setup()).finally(() => {
      this.initializing = null;
    });
    return this.initializing;
  }

  private async setup(): Promise<void> {
    this.dispose();
    const [{ buildURL, GOOG_API_KEY }, { BotGuardClient, parseChallengeData }, { WebPoMinter }] = await Promise.all([
      import('bgutils-js/utils'),
      import('bgutils-js/botguard'),
      import('bgutils-js/webpo'),
    ]);
    const headers = {
      'content-type': 'application/json+protobuf',
      'x-goog-api-key': GOOG_API_KEY,
      'x-user-agent': 'grpc-web-javascript/0.1',
    };

    const challengeResponse = await fetch(buildURL('Create', true), {
      method: 'POST',
      headers,
      body: JSON.stringify([REQUEST_KEY]),
    });
    const challenge = parseChallengeData(await challengeResponse.json());
    if (!challenge) throw new Error('BotGuard returned no challenge');
    this.challenge = challenge;

    const interpreter = challenge.interpreterJavascript?.privateDoNotAccessOrElseSafeScriptWrappedValue;
    if (!interpreter) throw new Error(`No BotGuard interpreter (hash ${challenge.interpreterHash})`);
    if (!document.getElementById(challenge.interpreterHash!)) {
      const script = document.createElement('script');
      script.type = 'text/javascript';
      script.id = challenge.interpreterHash!;
      script.textContent = interpreter;
      document.head.appendChild(script);
    }

    this.client = await BotGuardClient.create({
      globalObject: globalThis,
      globalName: challenge.globalName,
      program: challenge.program,
    });

    const webPoSignalOutput: WebPoSignalOutput = [];
    const botguardResponse = await this.client.snapshot({ webPoSignalOutput });
    const bridge = getBridge();
    if (!bridge) throw new Error('BotGuard needs the extension to request an integrity token');
    const integrityResponse = await bridge.call('yt.fetch', {
      url: buildURL('GenerateIT', true),
      method: 'POST',
      headers,
      body: JSON.stringify([REQUEST_KEY, botguardResponse]),
    });
    if (integrityResponse.status !== 200) throw new Error(`GenerateIT answered ${integrityResponse.status}`);
    const [integrityToken, ttlSeconds] = JSON.parse(integrityResponse.body) as [string?, number?];
    if (!integrityToken) throw new Error(`No integrity token (interpreter ${challenge.interpreterHash})`);

    this.minter = await WebPoMinter.create({ integrityToken }, webPoSignalOutput);
    this.expiresAt = Date.now() + (typeof ttlSeconds === 'number' ? ttlSeconds * 1000 : DEFAULT_TTL_MS);
    this.tokens.clear();
    trackLog.i(`BotGuard ready; integrity token valid for ${Math.round((this.expiresAt - Date.now()) / 60_000)} min`);
  }

  async coldStartToken(contentBinding: string): Promise<string> {
    const { createColdStartToken } = await import('bgutils-js/webpo');
    return createColdStartToken(contentBinding);
  }

  async mint(contentBinding: string): Promise<string | undefined> {
    const known = this.tokens.get(contentBinding);
    if (known && Date.now() < this.expiresAt - RENEW_MARGIN_MS) return known;
    for (let attempt = 0; attempt < 2; attempt++) {
      try {
        if (attempt > 0) this.expiresAt = 0;
        await this.init();
        if (!this.minter) return undefined;
        const token = await this.minter.mintAsWebsafeString(contentBinding);
        this.tokens.set(contentBinding, token);
        trackLog.d('WebPO token minted', contentBinding);
        return token;
      } catch (error) {
        trackLog.w(`WebPO mint failed (attempt ${attempt + 1}): ${String(error)}`, contentBinding);
      }
    }
    return undefined;
  }

  private dispose() {
    this.client?.shutdown();
    this.client = undefined;
    this.minter = undefined;
    if (this.challenge?.interpreterHash) document.getElementById(this.challenge.interpreterHash)?.remove();
    this.challenge = undefined;
  }
}

export const botguard = new BotguardService();
