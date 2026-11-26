#!/usr/bin/env node
/**
 * 把 `src/editor/index.ts` 打成一个自包含的 ESM 文件（dist/editor/index.js）。
 *
 * 为什么要 bundle：
 *   - 宿主项目不一定装了 CodeMirror，也不该为了用一个编辑器再装 10 个包；
 *   - 插件对外只暴露 `capacitor-python/editor` 一个入口，减少版本冲突
 *     （CodeMirror 的两个副本会出现 `Unrecognized extension value` 之类的问题）。
 *
 * 这一步在 `npm run build` 里、tsc 之后执行；tsc 只负责生成 .d.ts 与主入口。
 */
import { build } from 'esbuild';
import { mkdir } from 'node:fs/promises';

await mkdir('dist/editor', { recursive: true });
await build({
  entryPoints: ['src/editor/index.ts'],
  outfile: 'dist/editor/index.js',
  bundle: true,
  format: 'esm',
  platform: 'browser',
  target: ['es2020', 'safari15', 'chrome90'],
  minify: true,
  sourcemap: false,
  legalComments: 'none',
  logLevel: 'info',
});
console.log('[capacitor-python] editor bundle written to dist/editor/index.js');
