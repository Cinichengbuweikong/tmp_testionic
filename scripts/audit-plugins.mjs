// 统一审计：插件结构、jsName、方法数、包字段、文档、已知陈旧引用
import { readdir, readFile, stat } from 'node:fs/promises';
import path from 'node:path';

const root = process.cwd();
const pluginsDir = path.join(root, 'plugins');

const exists = async (p) => {
  try {
    await stat(p);
    return true;
  } catch {
    return false;
  }
};
const read = (p) => readFile(p, 'utf8').catch(() => '');
const count = (text, re) => (text.match(re) || []).length;

async function walk(dir, filter) {
  const out = [];
  let entries;
  try {
    entries = await readdir(dir, { withFileTypes: true });
  } catch {
    return out;
  }
  for (const e of entries) {
    const full = path.join(dir, e.name);
    if (e.isDirectory()) out.push(...(await walk(full, filter)));
    else if (filter(e.name)) out.push(full);
  }
  return out;
}

const stale = [
  ['getAppDirectories', /getAppDirectories/],
  ['copyToAppStorage', /copyToAppStorage/],
  ['capacitor-app-state', /capacitor-app-state/],
  ['PluginCall.getLong', /call\.getLong\(/],
  ['TODO/FIXME', /\b(TODO|FIXME)\b/],
];

const entries = (await readdir(pluginsDir, { withFileTypes: true })).filter((e) => e.isDirectory());
const rows = [];
const issues = [];

for (const entry of entries) {
  const dir = path.join(pluginsDir, entry.name);
  const pkgRaw = await read(path.join(dir, 'package.json'));
  if (!pkgRaw) continue;
  const pkg = JSON.parse(pkgRaw);

  const javaFiles = await walk(path.join(dir, 'android', 'src', 'main', 'java'), (n) => n.endsWith('.java'));
  let javaName = null;
  let javaMethods = 0;
  let javaText = '';
  for (const file of javaFiles) {
    const text = await read(file);
    javaText += text;
    javaMethods += count(text, /@PluginMethod/g);
    const m = text.match(/@CapacitorPlugin\(\s*name\s*=\s*"([^"]+)"/);
    if (m) javaName = m[1];
  }

  const swiftFiles = await walk(path.join(dir, 'ios', 'Sources'), (n) => n.endsWith('.swift'));
  let swiftName = null;
  let swiftMethods = 0;
  let swiftText = '';
  for (const file of swiftFiles) {
    const text = await read(file);
    swiftText += text;
    const m = text.match(/jsName\s*=\s*"([^"]+)"/);
    if (m) swiftName = m[1];
    const block = text.match(/pluginMethods:\s*\[CAPPluginMethod\]\s*=\s*\[([\s\S]*?)\n\s*\]/);
    if (block) swiftMethods += count(block[1], /CAPPluginMethod\(name:/g);
  }

  const tsSources = await walk(path.join(dir, 'src'), (n) => n.endsWith('.ts'));
  const indexText = await read(path.join(dir, 'src', 'index.ts'));
  let tsName = null;
  for (const file of tsSources) {
    const text = await read(file);
    const m = text.match(/registerPlugin<[^>]+>\(\s*'([^']+)'/);
    if (m) {
      tsName = m[1];
      break;
    }
  }
  const definitions = await read(path.join(dir, 'src', 'definitions.ts'));
  const iface = definitions.match(/export interface \w+Plugin \{([\s\S]*?)\n\}/);
  const tsSigs = iface ? [...iface[1].matchAll(/^\s{2}(\w+)(\?)?\(.*?\):\s*Promise</gm)].map((m) => m[1]) : [];
  const listenerMethods = new Set(['addListener', 'removeAllListeners', 'removeListener', 'addListeners']);
  const tsMethods = tsSigs.filter((name) => !listenerMethods.has(name)).length;

  const readme = await exists(path.join(dir, 'README.md'));
  const intro = await exists(path.join(dir, 'INTRO.md'));
  const pkgFields = {
    capacitor: !!(pkg.capacitor?.android?.src && pkg.capacitor?.ios?.src),
    main: !!pkg.main,
    types: !!pkg.types,
    distInFiles: (pkg.files || []).some((f) => f.startsWith('dist')),
    peer: !!(pkg.peerDependencies && pkg.peerDependencies['@capacitor/core']),
  };

  // 陈旧引用只扫代码，不扫文档（README/INTRO 里的历史说明是故意的）
  const scanned = [javaText, swiftText, indexText, definitions].join("\n");
  const staleHits = stale.filter(([, re]) => re.test(scanned)).map(([name]) => name);

  rows.push({
    plugin: entry.name,
    version: pkg.version,
    javaName,
    swiftName,
    tsName,
    methods: { ts: tsMethods, java: javaMethods, swift: swiftMethods },
    pkg: pkgFields,
    docs: { readme, intro },
    stale: staleHits,
  });

  const nameOk = javaName && swiftName && tsName && javaName === swiftName && javaName === tsName;
  if (!nameOk) issues.push(`${entry.name}: jsName 不一致 java=${javaName} swift=${swiftName} ts=${tsName}`);
  if (tsMethods !== javaMethods) issues.push(`${entry.name}: 方法数 TS(${tsMethods}) != Java(${javaMethods})`);
  if (swiftMethods !== 0 && swiftMethods !== tsMethods) issues.push(`${entry.name}: 方法数 TS(${tsMethods}) != Swift(${swiftMethods})`);
  if (!pkgFields.capacitor) issues.push(`${entry.name}: package.json 缺 capacitor.android/ios.src`);
  if (!pkgFields.main || !pkgFields.types) issues.push(`${entry.name}: package.json 缺 main/types`);
  if (!pkgFields.distInFiles) issues.push(`${entry.name}: package.json files 缺 dist`);
  if (!pkgFields.peer) issues.push(`${entry.name}: 缺 peerDependencies @capacitor/core`);
  if (!readme) issues.push(`${entry.name}: 缺 README.md`);
  if (staleHits.length) issues.push(`${entry.name}: 陈旧引用 -> ${staleHits.join(', ')}`);
}

console.log('plugin'.padEnd(30), 'ver'.padEnd(7), 'jsName'.padEnd(20), 'TS/Java/Swift'.padEnd(13), 'pkg', 'docs', 'stale');
for (const r of rows) {
  const pkgOk = r.pkg.capacitor && r.pkg.main && r.pkg.types && r.pkg.distInFiles && r.pkg.peer;
  console.log(
    r.plugin.padEnd(30),
    String(r.version).padEnd(7),
    String(r.tsName).padEnd(20),
    `${r.methods.ts}/${r.methods.java}/${r.methods.swift}`.padEnd(13),
    (pkgOk ? 'ok' : 'BAD').padEnd(4),
    `${r.docs.readme ? 'R' : '-'}${r.docs.intro ? 'I' : '-'}`.padEnd(5),
    r.stale.join(',')
  );
}
console.log(`\n插件数: ${rows.length}`);
console.log(issues.length ? `问题 ${issues.length} 条:\n- ` + issues.join('\n- ') : '未发现结构性问题');
