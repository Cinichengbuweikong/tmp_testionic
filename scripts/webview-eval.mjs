// Evaluates a JS expression inside the running app's WebView via Chrome DevTools Protocol.
// Requires: adb forward tcp:<port> localabstract:webview_devtools_remote_<pid>
//
// Usage:
//   node scripts/webview-eval.mjs --port 9223 --file scripts/smoke-test.js
//   node scripts/webview-eval.mjs --port 9223 --expr "location.hash"

import { readFile } from 'node:fs/promises';

const args = process.argv.slice(2);
const arg = (name, fallback) => {
  const i = args.indexOf(`--${name}`);
  return i >= 0 ? args[i + 1] : fallback;
};

const port = arg('port', '9222');
let expression = arg('expr');
const file = arg('file');
if (!expression && file) {
  expression = await readFile(file, 'utf8');
}
if (!expression) {
  console.error('missing --expr or --file');
  process.exit(2);
}

const targets = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
const page =
  targets.find((t) => t.type === 'page' && /index\.html|localhost/.test(t.url)) ??
  targets.find((t) => t.type === 'page') ??
  targets[0];

if (!page) {
  console.error('no debuggable webview target found');
  process.exit(2);
}

const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((resolve, reject) => {
  ws.onopen = resolve;
  ws.onerror = (e) => reject(new Error(`websocket error: ${e.message ?? e}`));
});

let nextId = 0;
const pending = new Map();
ws.onmessage = (event) => {
  const msg = JSON.parse(event.data);
  if (msg.id && pending.has(msg.id)) {
    pending.get(msg.id)(msg);
    pending.delete(msg.id);
  }
};

const send = (method, params) =>
  new Promise((resolve) => {
    const id = ++nextId;
    pending.set(id, resolve);
    ws.send(JSON.stringify({ id, method, params }));
  });

const response = await Promise.race([
  send('Runtime.evaluate', {
    expression,
    awaitPromise: true,
    returnByValue: true,
    userGesture: true,
  }),
  new Promise((_, reject) => {
    // 防止 WebView 进程被杀 / 页面卡死时一直挂着（注意不要 unref，否则事件循环空了会提前退出）
    // 冒烟脚本（smoke-test.js）步骤越来越多，执行时间已超过 30s，这里放宽到 120s
    setTimeout(() => reject(new Error('CDP evaluate timeout (120s)')), 120000);
  }),
]).catch((err) => {
  console.error(String(err && err.message ? err.message : err));
  process.exit(4);
});

ws.close();

if (response.error) {
  console.error(JSON.stringify(response.error));
  process.exit(3);
}
const details = response.result?.exceptionDetails;
if (details) {
  console.error(JSON.stringify(details.exception?.description ?? details.text ?? details));
  process.exit(3);
}

console.log(JSON.stringify(response.result?.result?.value ?? null, null, 2));
