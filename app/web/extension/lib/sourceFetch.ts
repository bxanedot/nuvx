import { checkSourceRequest, type HttpResponse, type SourceFetchParams } from '@nuvx/shared/bridge';
import { patternOf } from './lyricHosts';
import { serialiseResponse } from './httpResponse';

const RJ_AGENT = 'Radio Javan/5.0.0 (Desktop) com.radioJavan.rj.desktop';

export class SourcePermissionError extends Error {
  readonly code = 'permission';
}

export async function sourceFetch({ provider, url: raw }: SourceFetchParams): Promise<HttpResponse> {
  const url = checkSourceRequest(provider, raw);
  if (!url) throw Object.assign(new Error('source.fetch doesn’t serve that request'), { code: 'forbidden' });
  if (!(await browser.permissions.contains({ origins: [patternOf(url.origin)] }))) {
    throw new SourcePermissionError(`nuvx needs permission to reach ${url.host}.`);
  }
  const response = await fetch(url, {
    method: 'GET',
    headers: provider === 'radiojavan' ? { 'x-rj-user-agent': RJ_AGENT } : {},
    credentials: 'omit',
    redirect: 'error',
    signal: AbortSignal.timeout(15_000),
  });
  return serialiseResponse(response, true);
}
