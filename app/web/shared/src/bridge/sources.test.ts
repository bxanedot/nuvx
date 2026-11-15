import { describe, expect, it } from 'vitest';
import { checkSourceRequest } from './sources';

const jio = (q: string) => `https://www.jiosaavn.com/api.php?${q}`;

describe('checkSourceRequest', () => {
  it('accepts the documented provider calls', () => {
    expect(checkSourceRequest('jiosaavn', jio('__call=search.getResults&q=a+b&n=25&p=1&api_version=4&ctx=android'))).not.toBeNull();
    expect(checkSourceRequest('jiosaavn', jio('__call=song.generateAuthToken&url=abc&bitrate=320'))).not.toBeNull();
    expect(checkSourceRequest('radiojavan', 'https://rj-deskcloud.com/api2/search?query=a%20b')).not.toBeNull();
    expect(checkSourceRequest('radiojavan', 'https://rj-deskcloud.com/api2/mp3?id=12')).not.toBeNull();
  });

  it('rejects other hosts, paths, calls, keys and schemes', () => {
    const bad: [string, string][] = [
      ['jiosaavn', 'http://www.jiosaavn.com/api.php?__call=search.getResults'],
      ['jiosaavn', 'https://jiosaavn.com/api.php?__call=search.getResults'],
      ['jiosaavn', 'https://www.jiosaavn.com.evil.test/api.php?__call=search.getResults'],
      ['jiosaavn', 'https://www.jiosaavn.com/other.php?__call=search.getResults'],
      ['jiosaavn', jio('__call=user.getDetails')],
      ['jiosaavn', jio('__call=search.getResults&__call=song.generateAuthToken')],
      ['jiosaavn', jio('__call=search.getResults&token=x')],
      ['jiosaavn', 'https://user@www.jiosaavn.com/api.php?__call=search.getResults'],
      ['jiosaavn', 'https://www.jiosaavn.com:444/api.php?__call=search.getResults'],
      ['radiojavan', 'https://rj-deskcloud.com/api2/login?query=a'],
      ['radiojavan', 'https://rj-deskcloud.com/api2/search?query=a&token=1'],
      ['radiojavan', 'https://rj-deskcloud.com/api2/search/../mp3?id=1&x=1'],
      ['radiojavan', 'https://www.jiosaavn.com/api.php?__call=search.getResults'],
      ['jiosaavn', 'https://rj-deskcloud.com/api2/search?query=a'],
      ['other', 'https://rj-deskcloud.com/api2/search?query=a'],
    ];
    for (const [provider, url] of bad) expect(checkSourceRequest(provider, url), url).toBeNull();
    expect(checkSourceRequest('radiojavan', 'https://rj-deskcloud.com/api2/search?query=' + 'x'.repeat(2000))).toBeNull();
    expect(checkSourceRequest('jiosaavn', 42)).toBeNull();
  });
});
