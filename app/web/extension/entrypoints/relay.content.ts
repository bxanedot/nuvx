import {
  BRIDGE_CHANNEL,
  errorResponse,
  EVENTS_PORT,
  isBridgeRequest,
  type BridgeEvent,
  type BridgeEventMessage,
  type BridgeResponse,
} from '@nuvx/shared/bridge';

const RECONNECT_MS = 2000;

export default defineContentScript({
  matches: ['http://localhost/*'],
  include: ['firefox'],
  runAt: 'document_start',
  main() {
    window.addEventListener('message', (event: MessageEvent) => {
      if (event.source !== window || event.origin !== location.origin) return;
      if (!isBridgeRequest(event.data)) return;
      const request = event.data;
      browser.runtime
        .sendMessage(request)
        .then((response: BridgeResponse) => window.postMessage(response, location.origin))
        .catch((error: unknown) =>
          window.postMessage(errorResponse(request.id, 'internal', String(error)), location.origin),
        );
    });

    let port: Browser.runtime.Port | null = null;
    const connect = () => {
      if (port || document.hidden) return;
      port = browser.runtime.connect({ name: EVENTS_PORT });
      port.onMessage.addListener((payload: BridgeEvent) => {
        const message: BridgeEventMessage = { channel: BRIDGE_CHANNEL, direction: 'event', payload };
        window.postMessage(message, location.origin);
      });
      port.onDisconnect.addListener(() => {
        port = null;
        setTimeout(connect, RECONNECT_MS);
      });
    };
    document.addEventListener('visibilitychange', connect);
    connect();
  },
});
