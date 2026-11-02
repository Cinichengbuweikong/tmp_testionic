// 逐个构建 plugins/ 下的本地插件。
// 插件的 dist/ 不入库（.gitignore），所以应用构建前必须先构建插件——
// 本地和 CI 都走这一个脚本，避免两边行为不一致。
import { readdir, readFile, stat } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import path from 'node:path';

const root = process.cwd();
const pluginsDir = path.join(root, 'plugins');
const isWindows = process.platform === 'win32';

// 优先用 node 直接跑 npm-cli.js，避免 shell:true 带来的 DEP0190 警告与转义问题
const npmCli = process.env.npm_execpath && process.env.npm_execpath.endsWith('.js') ? process.env.npm_execpath : null;

const exists = async (p) => {
  try {
    await stat(p);
    return true;
  } catch {
    return false;
  }
};

const run = (args, cwd) => {
  const command = npmCli ? process.execPath : isWindows ? 'npm.cmd' : 'npm';
  const fullArgs = npmCli ? [npmCli, ...args] : args;
  console.log(`$ npm ${args.join(' ')}`);
  const result = spawnSync(command, fullArgs, {
    cwd,
    stdio: 'inherit',
    shell: !npmCli && isWindows,
  });
  if (result.status !== 0) {
    console.error(`\n[build-plugins] 失败：${path.basename(cwd)}`);
    process.exit(result.status ?? 1);
  }
};

const entries = (await readdir(pluginsDir, { withFileTypes: true })).filter((e) => e.isDirectory());
let built = 0;

for (const entry of entries) {
  const dir = path.join(pluginsDir, entry.name);
  let pkg;
  try {
    pkg = JSON.parse(await readFile(path.join(dir, 'package.json'), 'utf8'));
  } catch {
    continue; // 不是插件目录
  }

  if (!pkg.scripts?.build) {
    console.log(`[build-plugins] 跳过 ${entry.name}（没有 build 脚本）`);
    continue;
  }

  console.log(`\n=== ${pkg.name ?? entry.name} ===`);
  const hasModules = await exists(path.join(dir, 'node_modules'));
  if (hasModules) {
    run(['install', '--no-audit', '--no-fund', '--prefer-offline'], dir);
  } else {
    run(['ci', '--no-audit', '--no-fund'], dir);
  }
  run(['run', 'build'], dir);
  built++;
}

console.log(`\n[build-plugins] 完成，共构建 ${built} 个插件`);
