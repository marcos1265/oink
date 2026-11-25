import { readFileSync } from 'node:fs';
import { expect, it } from 'vitest';
import { sponsorList } from './remoteList';

it('returns null locally without contacting any remote server', async () => {
  expect(await sponsorList()).toBeNull();
});

it('keeps the WebView CSP and capabilities closed to remote images and connections', () => {
  const config = JSON.parse(readFileSync(new URL('../../src-tauri/tauri.conf.json', import.meta.url), 'utf8'));
  const directives = Object.fromEntries(config.app.security.csp.split(';').map((value: string) => {
    const [name, ...sources] = value.trim().split(/\s+/); return [name, sources];
  }));
  expect(directives['img-src']).toEqual(["'self'", 'data:']);
  expect(directives['connect-src']).toEqual(['ipc:', 'http://ipc.localhost']);
});
