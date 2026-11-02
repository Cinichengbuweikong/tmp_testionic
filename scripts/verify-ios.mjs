// Static checks for the iOS side of the plugins.
//
// On Windows there is no Xcode, so this script cannot compile anything. It only
// verifies that the iOS wiring is internally consistent (Package.swift present,
// jsName matches registerPlugin, exported methods match pluginMethods, and the
// app-level CapApp-SPM package references each plugin). Real compilation happens
// in CI: .github/workflows/ios.yml
import { readdir, readFile, stat } from 'node:fs/promises';
import path from 'node:path';

const root = process.cwd();
const problems = [];
const notes = [];
const checked = [];

const exists = async (p) => {
  try {
    await stat(p);
    return true;
  } catch {
    return false;
  }
};

const read = (p) => readFile(p, 'utf8');

const appPackagePath = path.join(root, 'ios', 'App', 'CapApp-SPM', 'Package.swift');
let appPackage = '';
if (await exists(appPackagePath)) {
  appPackage = await read(appPackagePath);
} else {
  problems.push('ios/App/CapApp-SPM/Package.swift missing — run `npx cap add ios` / `npx cap sync ios`');
}

const pluginsDir = path.join(root, 'plugins');
const entries = await readdir(pluginsDir, { withFileTypes: true });

for (const entry of entries) {
  if (!entry.isDirectory()) continue;
  const dir = path.join(pluginsDir, entry.name);
  const packageJsonPath = path.join(dir, 'package.json');
  if (!(await exists(packageJsonPath))) continue;

  const pkg = JSON.parse(await read(packageJsonPath));
  const iosConfig = pkg.capacitor?.ios;
  if (!iosConfig) {
    notes.push(`${entry.name}: 只声明了 Android，跳过 iOS 检查`);
    continue;
  }

  const swiftPackagePath = path.join(dir, 'Package.swift');
  if (!(await exists(swiftPackagePath))) {
    problems.push(`${entry.name}: package.json 里声明了 capacitor.ios，但缺少 Package.swift`);
    continue;
  }

  const swiftPackage = await read(swiftPackagePath);
  const packageName = /name:\s*"([^"]+)"/.exec(swiftPackage)?.[1];
  if (!packageName) {
    problems.push(`${entry.name}: Package.swift 里解析不到 package name`);
    continue;
  }

  // collect swift sources
  const sourcesDir = path.join(dir, 'ios', 'Sources');
  const swiftFiles = [];
  const walk = async (d) => {
    for (const item of await readdir(d, { withFileTypes: true })) {
      const p = path.join(d, item.name);
      if (item.isDirectory()) await walk(p);
      else if (item.name.endsWith('.swift')) swiftFiles.push(p);
    }
  };
  if (await exists(sourcesDir)) await walk(sourcesDir);
  if (swiftFiles.length === 0) {
    problems.push(`${entry.name}: ios/Sources 下没有 Swift 文件`);
    continue;
  }

  const swiftSource = (await Promise.all(swiftFiles.map(read))).join('\n');

  if (!/@objc\(\w+Plugin\)/.test(swiftSource)) {
    problems.push(`${entry.name}: Swift 类缺少 @objc(...Plugin) 导出`);
  }
  if (!/CAPBridgedPlugin/.test(swiftSource)) {
    problems.push(`${entry.name}: Swift 类没有实现 CAPBridgedPlugin`);
  }

  const jsName = /jsName\s*=\s*"([^"]+)"/.exec(swiftSource)?.[1];
  const indexTs = await read(path.join(dir, 'src', 'index.ts'));
  const registeredName = /registerPlugin<[^>]*>\(\s*['"]([^'"]+)['"]/.exec(indexTs)?.[1];
  if (!jsName || !registeredName) {
    problems.push(`${entry.name}: 无法解析 jsName（Swift）或 registerPlugin 名称（index.ts）`);
  } else if (jsName !== registeredName) {
    problems.push(`${entry.name}: jsName "${jsName}" 与 registerPlugin("${registeredName}") 不一致`);
  }

  // exported TS methods vs pluginMethods array
  const definitionsTs = await read(path.join(dir, 'src', 'definitions.ts'));
  const interfaceBody = definitionsTs.split(/export interface \w+Plugin\s*\{/)[1]?.split('\n}')[0] ?? '';
  const tsMethods = [...interfaceBody.matchAll(/^\s{2}(\w+)\s*\(/gm)].map((m) => m[1]);
  const swiftMethods = [...swiftSource.matchAll(/CAPPluginMethod\(name:\s*"(\w+)"/g)].map((m) => m[1]);

  const missingInSwift = tsMethods.filter((m) => !swiftMethods.includes(m));
  const extraInSwift = swiftMethods.filter((m) => !tsMethods.includes(m));
  if (missingInSwift.length) {
    problems.push(`${entry.name}: TS 声明了但 Swift 未导出：${missingInSwift.join(', ')}`);
  }
  if (extraInSwift.length) {
    problems.push(`${entry.name}: Swift 导出了但 TS 未声明：${extraInSwift.join(', ')}`);
  }

  // app-level package wiring
  if (appPackage) {
    if (!appPackage.includes(`name: "${packageName}"`)) {
      problems.push(`${appPackagePath}: 未引用插件包 ${packageName}（需要 npx cap sync ios）`);
    }
    if (!appPackage.includes(`.product(name: "${packageName}"`)) {
      problems.push(`${appPackagePath}: 未把 ${packageName} 加进 target 依赖`);
    }
  }

  checked.push(`${entry.name} (${packageName}, ${swiftMethods.length} methods)`);
}

console.log('iOS 静态检查（注意：这不能替代真正的 Xcode 编译）\n');
for (const line of checked) console.log(`  ✓ ${line}`);
for (const line of notes) console.log(`  - ${line}`);

if (problems.length) {
  console.error('\n发现问题：');
  for (const p of problems) console.error(`  ✗ ${p}`);
  process.exit(1);
}

console.log('\n静态检查通过。Swift 编译请在 GitHub Actions（.github/workflows/ios.yml）或任意 macOS 上跑。');
