#!/usr/bin/env node
/**
 * 下载并把 Pyodide 运行时资源放进 App 的 Web 资源目录（public/python-runtime/<version>/）。
 *
 * 为什么需要这个脚本：
 *   - Pyodide 的核心文件 ~13MB，不适合提交进仓库；
 *   - App 必须能**离线**运行 Python，所以资源要打进 App 包，而不是运行时从 CDN 拉；
 *   - 未来升级 Python 只需要改 `PYODIDE_VERSION`（或命令行参数），旧版本目录可以并存，
 *     插件按 `runtime.json` 选择版本 —— 这是"未来其他人/AI 升级 Python 版本"的入口。
 *
 * 用法：
 *   node plugins/capacitor-python/scripts/fetch-python-runtime.mjs [--version 314.0.8] [--out <dir>]
 *   默认输出到 <repo>/public/python-runtime/<version>/
 *
 * 生成的目录：
 *   pyodide.mjs          入口（ESM）
 *   pyodide.asm.mjs      Emscripten glue（ESM，Pyodide 0.27+ 起）
 *   pyodide.asm.wasm     CPython 的 WebAssembly 二进制
 *   python_stdlib.zip    标准库
 *   pyodide-lock.json    包索引（micropip 用）
 *   manifest.json        本插件写入的元信息（版本、文件、大小、sha256、引擎要求）
 */
import { createHash } from 'node:crypto';
import { mkdtemp, mkdir, readFile, rm, writeFile, copyFile, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { execFileSync } from 'node:child_process';

const RUNTIME_FILES = [
  'pyodide.mjs',
  'pyodide.asm.mjs',
  'pyodide.asm.wasm',
  'python_stdlib.zip',
  'pyodide-lock.json',
];

const args = process.argv.slice(2);
const argOf = (name, fallback) => {
  const i = args.indexOf(`--${name}`);
  return i >= 0 && args[i + 1] ? args[i + 1] : fallback;
};

const version = argOf('version', process.env.PYODIDE_VERSION || '314.0.8');
const repoRoot = resolve(dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')), '../../..');
const outDir = resolve(argOf('out', join(repoRoot, 'public', 'python-runtime', version)));

console.log(`[python-runtime] version=${version}`);
console.log(`[python-runtime] out=${outDir}`);

const tmp = await mkdtemp(join(tmpdir(), 'pyodide-'));
try {
  console.log('[python-runtime] downloading pyodide npm package...');
  // Windows 上 npm 是 .cmd，Node >= 18 直接 execFile 会 EINVAL，必须走 shell
  execFileSync('npm', ['pack', `pyodide@${version}`, '--silent'], {
    cwd: tmp,
    stdio: ['ignore', 'ignore', 'inherit'],
    shell: process.platform === 'win32',
  });
  const { readdir } = await import('node:fs/promises');
  const tgz = (await readdir(tmp)).find((f) => f.endsWith('.tgz'));
  if (!tgz) throw new Error('npm pack produced no tarball');
  console.log(`[python-runtime] extracting ${tgz}`);
  execFileSync('tar', ['-xzf', tgz], { cwd: tmp, stdio: 'inherit' });

  await mkdir(outDir, { recursive: true });
  const files = [];
  for (const name of RUNTIME_FILES) {
    const src = join(tmp, 'package', name);
    await stat(src); // 文件不存在会抛错，提示 Pyodide 版本不兼容
    const dst = join(outDir, name);
    await copyFile(src, dst);
    const bytes = await readFile(dst);
    files.push({
      name,
      bytes: bytes.byteLength,
      sha256: createHash('sha256').update(bytes).digest('hex'),
    });
    console.log(`[python-runtime]   ${name}  ${(bytes.byteLength / 1024 / 1024).toFixed(2)} MB`);
  }

  const manifest = {
    engine: 'pyodide',
    pyodideVersion: version,
    pythonVersion: version.startsWith('3') ? `${version[0]}.${version.slice(1, 3)}` : 'unknown',
    generatedAt: new Date().toISOString(),
    // 这些是 Pyodide 官方声明的运行环境下限；低于它的 WebView 解析不了 wasm/glue 里的新语法
    minimum: {
      androidWebView: 90,
      ios: '16.4',
    },
    files,
  };
  await writeFile(join(outDir, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n', 'utf8');
  console.log(`[python-runtime] done: ${files.length} files, manifest written`);

  // worker.mjs 与运行时版本无关，放在 python-runtime/ 根目录（indexURL 指向 <version>/）
  const workerSrc = resolve(dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')), '..', 'runtime', 'worker.mjs');
  const workerDst = resolve(outDir, '..', 'worker.mjs');
  await copyFile(workerSrc, workerDst);
  console.log(`[python-runtime] worker copied to ${workerDst}`);
} finally {
  await rm(tmp, { recursive: true, force: true });
}
