// Runs YouTube's signature code away from the page: no DOM, no storage, no extension bridge.
self.onmessage = (event: MessageEvent<{ id: number; code: string }>) => {
  const { id, code } = event.data;
  try {
    self.postMessage({ id, result: new Function(code)() });
  } catch (error) {
    self.postMessage({ id, error: String(error) });
  }
};
