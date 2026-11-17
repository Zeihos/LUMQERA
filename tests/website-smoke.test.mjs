import test, { before, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const port = 30000 + process.pid % 10000;
const base = 'http://127.0.0.1:' + port;
let child;

before(async () => {
  child = spawn(process.execPath, ['website/dev-server.mjs'], {
    cwd: root,
    env: { ...process.env, PORT: String(port) },
    stdio: ['ignore', 'pipe', 'pipe']
  });
  let output = '';
  child.stdout.on('data', chunk => { output += chunk.toString(); });
  child.stderr.on('data', chunk => { output += chunk.toString(); });
  for (let i = 0; i < 80; i++) {
    if (child.exitCode !== null) throw new Error('Site exited unexpectedly: ' + output);
    try {
      const response = await fetch(base + '/');
      if (response.ok) return;
    } catch { /* server not listening yet */ }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw new Error('Site never became ready: ' + output);
});

after(() => {
  if (child && child.exitCode === null) child.kill();
});

test('main page is served with the Russian LUMQERA title', async () => {
  const response = await fetch(base + '/');
  assert.equal(response.status, 200);
  assert.match(response.headers.get('content-type'), /^text\/html/);
  assert.match(await response.text(), /LUMQERA/);
});

test('static JavaScript and CSS are available', async () => {
  for (const url of ['/app.js', '/style.css']) {
    const response = await fetch(base + url);
    assert.equal(response.status, 200);
    assert.ok((await response.text()).length > 100);
  }
});

test('published ranking uses the expected JSON schema', async () => {
  const response = await fetch(base + '/data/public-stats.json');
  assert.equal(response.status, 200);
  const json = await response.json();
  assert.equal(json.schemaVersion, 1);
  assert.ok(Array.isArray(json.clans));
  assert.ok(Array.isArray(json.players));
  assert.ok(Array.isArray(json.outposts));
});

test('HEAD returns no body; POST is rejected', async () => {
  const head = await fetch(base + '/style.css', { method: 'HEAD' });
  assert.equal(head.status, 200);
  assert.equal(await head.text(), '');
  const post = await fetch(base + '/style.css', { method: 'POST' });
  assert.equal(post.status, 405);
});

test('unknown and traversal paths do not expose project files', async () => {
  const missing = await fetch(base + '/this-file-does-not-exist');
  assert.equal(missing.status, 404);
  const traversal = await fetch(base + '/%2e%2e%2fREADME.md');
  assert.ok([403, 404].includes(traversal.status));
  assert.ok(!(await traversal.text()).includes('## Быстрый старт'));
});
