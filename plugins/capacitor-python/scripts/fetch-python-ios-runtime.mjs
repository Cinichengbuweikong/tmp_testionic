#!/usr/bin/env node
/**
 * 下载并裁剪 iOS 原生 CPython 运行时（BeeWare Python-Apple-support）。
 *
 * 产物（都不入库，见 .gitignore）：
 *   plugins/capacitor-python/ios/Python.xcframework/          ← 静态 libPython + 头文件 + 扩展模块
 *   plugins/capacitor-python/ios/Sources/PythonPlugin/python/ ← 裁剪后的纯 Python stdlib（SPM 资源）
 *   plugins/capacitor-python/ios/Sources/PythonShim/include/python/ ← 头文件（给 C shim 用）
 *
 * 为什么要裁剪：完整 stdlib 46MB，其中 test/ 就 32MB；test/idlelib/tkinter/turtle/ensurepip
 * 对嵌入式运行时完全没用。裁剪后约 14MB。
 *
 * 用法：
 *   node plugins/capacitor-python/scripts/fetch-python-ios-runtime.mjs [--version 3.11-b13]
 *
 * 注意：扩展模块（lib-dynload/*.so）**没有**被转换/签名 —— iOS 要求可执行代码放在
 * Frameworks/ 并由 Xcode 的 embed 阶段签名，这必须由宿主 App 的 Run Script 完成（见 README）。
 * 本脚本只负责「纯 Python + 内建模块」能跑起来的那部分；ssl/sqlite3 等 C 扩展需要第二步。
 */
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { cp, mkdir, readdir, readFile, rm, stat, writeFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { tmpdir } from 'node:os';

const RELEASES = {
  '3.11-b13': {
    python: '3.11.18',
    url: 'https://github.com/beeware/Python-Apple-support/releases/download/3.11-b13/Python-3.11-iOS-support.b13.tar.gz',
    sha256: 'e1f55eef2b8c4812f63ee02f8284c8db067ef1a26e75b9a470934376ac197ea0',
    tag: 'b13',
    series: '3.11',
  },
};

const args = process.argv.slice(2);
const argOf = (name, fallback) => {
  const index = args.indexOf(`--${name}`);
  return index >= 0 && args[index + 1] ? args[index + 1] : fallback;
};

const release = argOf('version', '3.11-b13');
const info = RELEASES[release];
if (!info) {
  console.error(`[python-ios] 不支持的版本 "${release}"，可选：${Object.keys(RELEASES).join(', ')}`);
  process.exit(1);
}

const scriptDir = dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'));
const pluginDir = resolve(scriptDir, '..');
const iosDir = join(pluginDir, 'ios');
const xcframeworkDir = join(iosDir, 'Python.xcframework');
const stdlibTarget = join(iosDir, 'Sources', 'PythonPlugin', 'python', 'lib', `python${info.series}`);
const headersTarget = join(iosDir, 'Sources', 'PythonShim', 'python-headers');

const PRUNE = ['test', 'idlelib', 'tkinter', 'turtle.py', 'turtledemo', 'ensurepip', 'lib2to3', 'pydoc_data'];

async function exists(path) {
  try {
    await stat(path);
    return true;
  } catch {
    return false;
  }
}

async function sha256(file) {
  const hash = createHash('sha256');
  hash.update(await readFile(file));
  return hash.digest('hex');
}

console.log(`[python-ios] version=${release} (CPython ${info.python})`);
const temp = join(tmpdir(), `python-ios-${Date.now()}`);
await mkdir(temp, { recursive: true });

try {
  const tarball = join(temp, 'support.tar.gz');
  console.log('[python-ios] downloading…');
  const response = await fetch(info.url, { redirect: 'follow' });
  if (!response.ok) throw new Error(`HTTP ${response.status} ${info.url}`);
  await writeFile(tarball, Buffer.from(await response.arrayBuffer()));

  const digest = await sha256(tarball);
  if (digest !== info.sha256) {
    throw new Error(`sha256 不匹配：期望 ${info.sha256}，实际 ${digest}`);
  }
  console.log('[python-ios] sha256 ok');

  console.log('[python-ios] extracting…');
  execFileSync('tar', ['-xzf', tarball, '-C', temp], { stdio: 'inherit' });
  const extracted = join(temp, 'Python.xcframework');
  if (!(await exists(extracted))) throw new Error('解压后找不到 Python.xcframework');

  // 1) xcframework（静态 libPython + 头文件 + lib-dynload）
  if (await exists(xcframeworkDir)) await rm(xcframeworkDir, { recursive: true, force: true });
  await cp(extracted, xcframeworkDir, { recursive: true });
  console.log('[python-ios] Python.xcframework copied');

  // 2) 裁剪 stdlib 并放进 SPM 资源目录（SWIFT_PACKAGE 的 Bundle.module 能定位到）
  const sourceStdlib = join(extracted, 'lib', `python${info.series}`);
  if (!(await exists(sourceStdlib))) throw new Error(`找不到 stdlib：${sourceStdlib}`);
  if (await exists(stdlibTarget)) await rm(stdlibTarget, { recursive: true, force: true });
  await mkdir(stdlibTarget, { recursive: true });
  for (const entry of await readdir(sourceStdlib)) {
    if (PRUNE.includes(entry) || entry.startsWith('config-')) continue;
    await cp(join(sourceStdlib, entry), join(stdlibTarget, entry), { recursive: true });
  }
  console.log(`[python-ios] stdlib pruned -> ${stdlibTarget}`);

  // 3) 头文件（C shim 需要 Python.h；SPM 的 binaryTarget 不暴露头文件）
  const headersSource = join(extracted, 'ios-arm64', 'Python.framework', 'Headers');
  if (!(await exists(headersSource))) throw new Error('找不到 Python.framework/Headers');
  // Headers 里除了 Python.h 还有 cpython/ 子目录，必须整目录递归复制
  if (await exists(headersTarget)) await rm(headersTarget, { recursive: true, force: true });
  await mkdir(dirname(headersTarget), { recursive: true });
  await cp(headersSource, headersTarget, { recursive: true });
  console.log('[python-ios] headers copied');

  // 4) 元信息（CI/文档用）
  const stdlibSize = await dirSize(stdlibTarget);
  await writeFile(
    join(iosDir, 'PythonRuntime.json'),
    JSON.stringify(
      {
        engine: 'python-apple-support',
        release,
        pythonVersion: info.python,
        series: info.series,
        stdlibBytes: stdlibSize,
        pruned: PRUNE,
        note: 'lib-dynload 的 .so 需要宿主 App 的 Run Script 转 framework 并签名（iOS 限制），本脚本不含该步骤',
      },
      null,
      2,
    ) + '\n',
    'utf8',
  );
  console.log(`[python-ios] done: stdlib ${(stdlibSize / 1024 / 1024).toFixed(1)} MB`);
} finally {
  await rm(temp, { recursive: true, force: true });
}

async function dirSize(dir) {
  let total = 0;
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) total += await dirSize(path);
    else total += (await stat(path)).size;
  }
  return total;
}
