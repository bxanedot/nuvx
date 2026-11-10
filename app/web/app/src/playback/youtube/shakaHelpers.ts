// SPDX-License-Identifier: MIT

import shaka from 'shaka-player/dist/shaka-player.compiled';

export function asMap<K, V>(object: Record<string, V>): Map<K, V> {
  const map = new Map<K, V>();
  for (const key of Object.keys(object)) {
    map.set(key as K, object[key] as V);
  }
  return map;
}

export function createRecoverableError(message: string, info?: Record<string, unknown> | object) {
  return new shaka.util.Error(
    shaka.util.Error.Severity.RECOVERABLE,
    shaka.util.Error.Category.NETWORK,
    shaka.util.Error.Code.HTTP_ERROR,
    message,
    { info },
  );
}

export function headersToGenericObject(headers: Headers): Record<string, string> {
  const headersObj: Record<string, string> = {};
  headers.forEach((value, key) => {
    headersObj[key.trim()] = value;
  });
  return headersObj;
}

export function makeResponse(
  headers: Record<string, string>,
  data: BufferSource,
  status: number,
  uri: string,
  responseURL: string,
  request: shaka.extern.Request,
  requestType: shaka.net.NetworkingEngine.RequestType,
): shaka.extern.Response & { originalRequest: shaka.extern.Request } {
  if (status >= 200 && status <= 299 && status !== 202) {
    return {
      uri: responseURL || uri,
      originalUri: uri,
      data,
      status,
      headers,
      originalRequest: request,
      fromCache: !!headers['x-shaka-from-cache'],
    };
  }

  let responseText: string | null = null;
  try {
    responseText = shaka.util.StringUtils.fromBytesAutoDetect(data);
  } catch {
  }

  const severity =
    status === 401 || status === 403 ? shaka.util.Error.Severity.CRITICAL : shaka.util.Error.Severity.RECOVERABLE;

  throw new shaka.util.Error(
    severity,
    shaka.util.Error.Category.NETWORK,
    shaka.util.Error.Code.BAD_HTTP_STATUS,
    uri,
    status,
    responseText,
    headers,
    requestType,
    responseURL || uri,
  );
}

export function isGoogleVideoURL(url: string): boolean {
  if (url.startsWith('sabr://')) return true;
  const [path = '', query = ''] = url.split('?');
  if (path.endsWith('/videoplayback')) {
    const params = new URLSearchParams(query);
    return params.get('source') === 'youtube' || params.has('sabr') || params.has('lsig') || params.has('expire');
  }
  if (path.includes('/videoplayback/')) {
    const parts = path.split('/');
    return ['videoplayback', 'sabr', 'lsig', 'expire'].some((part) => parts.includes(part));
  }
  return false;
}
