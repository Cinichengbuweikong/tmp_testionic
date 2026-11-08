# capacitor-native-files

本地**文件系统**插件：系统原生文件/文件夹选择器 + **真实绝对路径**读写 + 文件夹管理。

> **0.2.0 起所有 API 都用真实文件系统绝对路径**（如 `/storage/emulated/0/Notes/a.md`），
> 不再使用 `content://` URI。可以直接拼路径、算父/子目录——Markdown 阅读器、笔记树这类
> 需要"按目录结构遍历"的应用可以正常实现。
>
> 与 [`capacitor-data-channel`](../capacitor-data-channel/) 配合：大数据读写走 data-channel，
> 选择/遍历/管理文件走本插件。

## 能力一览

| 功能 | API | 说明 |
| --- | --- | --- |
| 选择文件 | `pickFiles()` | ✅ 多选（`multiple: true`） |
| 选择文件夹 | `pickFolders()` | ❌ 单选（Android SAF 系统限制，iOS 保持一致） |
| 读文件 | `readFile()` | base64；可 `offset`/`length` 分块，单次上限 16MB |
| 写文件 | `writeFile()` | `create` / `overwrite` / `append`；父目录自动创建 |
| 删除文件 | `deleteFile()` | — |
| 列目录 | `listFolder()` | 文件夹优先排序，可 `recursive` |
| 新建文件夹 | `createFolder()` | `path` 是完整目标路径（递归创建） |
| 删除文件夹 | `deleteFolder()` | 空目录直接删；非空需 `recursive: true` |
| 重命名 | `renameFolder()` | 返回新路径（文件/文件夹都可用） |
| 信息 | `getInfo()` | `{ exists, isDirectory, size, mimeType, lastModified, readable, writable }` |
| 所有文件访问权限 | `checkAllFilesAccess()` / `requestAllFilesAccess()` | Android 11+ 打开系统设置页 |
| 常用目录 | `getAppFilesDir()` 等 **23 个单条路径 API** | 私有目录 / 公共目录 / 安装包路径，见下 |

## 安装

```bash
npm install ./plugins/capacitor-native-files
npx cap sync
```

> **Android 公共目录权限**（读写任意路径必需）：
>
> | 版本 | 需要 |
> | --- | --- |
> | ≤ 9 | `WRITE_EXTERNAL_STORAGE` |
> | 10 | `WRITE_EXTERNAL_STORAGE` + 宿主 `requestLegacyExternalStorage="true"` |
> | **11+** | **`MANAGE_EXTERNAL_STORAGE`** → 调 `requestAllFilesAccess()` 让用户去系统设置里开启 |
>
> **App 私有目录**（`getAppFilesDir()` / `getAppExternalFilesDir()` 等）**任何版本都免权限**。
> iOS 没有"公共目录"概念，用系统选择器选择文件夹后由插件持久化 security-scoped bookmark。

## 路径约定（重要）

所有 API 的 `path` 参数都是**绝对路径**：

| 平台 | 示例 |
| --- | --- |
| Android | `/storage/emulated/0/Notes/a.md`（内部存储）、`/storage/1A2B-3C4D/Books/x.md`（SD 卡） |
| iOS | `/var/mobile/Containers/Data/Application/<uuid>/Documents/Notes/a.md`（沙盒）<br>用户选择的 iCloud/文件 App 目录也是绝对路径（插件持有 security-scoped bookmark） |

系统选择器返回的 `content://`（Android）会在插件内解析成真实路径：

- `primary:Download/x` → `/storage/emulated/0/Download/x`
- `home:` → `/storage/emulated/0/Documents/...`
- `<卷 UUID>:Music` → `/storage/<UUID>/Music`
- MediaStore → 查 `_data` 列
- **云盘 / 第三方 SAF provider 没有 1:1 文件路径** → 会被跳过并在 `unmapped` 里返回（不会静默失败）

## 用法

### 0) 常用目录：一个方法查一条路径

从 **0.4.0** 起，原来"一次性返回所有目录"的 `getAppDirectories()` 被拆成 **23 个单条路径 API**：

- 只想要一个路径时，不必等其它路径都查完；
- **某条路径取不到（不存在 / 平台不支持）不会影响其它调用**——每个方法独立 resolve；
- 每个方法含义见下表，不再需要猜返回的 key 是什么。

统一返回 `{ path: string | null, reason?: string }`。`path` 为 `null` 表示**该平台没有这个目录**，
`reason` 会写 `NOT_APPLICABLE_ON_IOS`（iOS 无公共目录）或 `EXTERNAL_STORAGE_NOT_MOUNTED`（外置存储未挂载）。

```ts
import { NativeFiles } from 'capacitor-native-files';

const docs = await NativeFiles.getSharedDocumentsDir();
if (docs.path) {
  console.log(docs.path);            // /storage/emulated/0/Documents
} else {
  console.log(docs.reason);          // 例如 NOT_APPLICABLE_ON_IOS
}

// 逐级遍历：拿到根目录后就能自由拼路径
const root = (await NativeFiles.getSharedStorageDir()).path;   // /storage/emulated/0
const tree = await NativeFiles.listFolder({ path: root });
```

| 方法 | 含义 | Android | iOS |
| --- | --- | --- | --- |
| `getAppFilesDir()` | App 私有**内部文件**目录（免权限、卸载即删） | `/data/user/0/<pkg>/files` | `Library/Application Support` |
| `getAppCacheDir()` | App 私有**内部缓存**（系统可能清理） | `/data/user/0/<pkg>/cache` | `Library/Caches` |
| `getAppTempDir()` | 临时目录 | 同 `cache`（Android 无独立 tmp） | `tmp` |
| `getAppExternalFilesDir()` | App **外部私有**文件目录（免权限、卸载即删） | `/storage/emulated/0/Android/data/<pkg>/files` | `null` |
| `getAppExternalCacheDir()` | App 外部私有缓存 | `/storage/emulated/0/Android/data/<pkg>/cache` | `null` |
| `getAppDocumentsDir()` | **App 专属**文档目录 | `/storage/emulated/0/Android/data/<pkg>/files/Documents` | `Documents` |
| `getSharedStorageDir()` | **共享存储根目录** | `/storage/emulated/0` | `null` |
| `getDownloadsDir()` | 系统下载目录 | `/storage/emulated/0/Download` | `null` |
| `getSharedDocumentsDir()` | 系统文档目录（公共） | `/storage/emulated/0/Documents` | `null` |
| `getPicturesDir()` | 系统图片目录 | `/storage/emulated/0/Pictures` | `null` |
| `getDcimDir()` | 系统相机目录 | `/storage/emulated/0/DCIM` | `null` |
| `getMoviesDir()` | 系统视频目录 | `/storage/emulated/0/Movies` | `null` |
| `getMusicDir()` | 系统音乐目录 | `/storage/emulated/0/Music` | `null` |
| `getAlarmsDir()` | 系统闹钟铃声目录 | `/storage/emulated/0/Alarms` | `null` |
| `getNotificationsDir()` | 系统通知铃声目录 | `/storage/emulated/0/Notifications` | `null` |
| `getPodcastsDir()` | 系统播客目录 | `/storage/emulated/0/Podcasts` | `null` |
| `getRingtonesDir()` | 系统铃声目录 | `/storage/emulated/0/Ringtones` | `null` |
| `getAppDataDir()` | App 私有**数据根目录** | `/data/user/0/<pkg>` | 沙盒根 `NSHomeDirectory()` |
| `getAppExternalRootDir()` | App **外部私有根目录** | `/storage/emulated/0/Android/data/<pkg>` | `null` |
| `getNativeLibDir()` | native 库目录 | APK 内 `lib/<abi>` | `null` |
| `getLibraryDir()` | `Library` 目录 | `null` | `Library` |
| `getIcloudDocumentsDir()` | iCloud Documents 目录 | `null` | iCloud 容器 `Documents` |
| `getAppInstallPath()` | App 安装包路径 | APK 文件路径 | `.app` 包目录 |

几点说明：

1. **`path` 不为 `null` 不代表目录已存在**（系统目录可能还没创建）——它只表示"该目录应当在这里"。
   要判断存在性用 `getInfo({ path })`，要创建用 `createFolder({ path })`。
2. 「App 私有」与「公共」的区别：前者免权限、卸载即删、其它 App 读不到；后者用户可见，
   Android 11+ 需要「所有文件访问权限」。
3. Android 11+ **`Android/data`、`Android/obb` 即使有所有文件访问权限也不可访问**（系统限制），
   不要把数据放这两个目录。笔记类数据建议放 `getSharedDocumentsDir()` 下的自建子目录。
4. iOS 没有公共目录概念：上表 iOS 列为 `null` 的方法都会返回 `{ path: null, reason: 'NOT_APPLICABLE_ON_IOS' }`。
5. Android 11+ 读写公共目录需要 MANAGE_EXTERNAL_STORAGE（Google Play 上架有政策限制）；如果只读写自己目录，用 appExternalFilesDir 就免权限
6. OS 的绝对路径只在"已选择过的祖先目录"范围内有效（bookmark 授权）。假设你的 Markdown 阅读器第一次让用户选一次根目录，之后所有子路径都能用，重启也有效（bookmark 已持久化）。

### 1) 选文件夹 → 得到绝对路径 → 拼路径遍历（Markdown 阅读器场景）

```ts
const { folders, cancelled } = await NativeFiles.pickFolders();
if (cancelled) return;

const root = folders[0].path;                  // 例如 /storage/emulated/0/Notes
const { entries } = await NativeFiles.listFolder({ path: root });

for (const entry of entries) {
  if (entry.isDirectory) {
    // 直接拼子目录 —— 这就是"真实路径"的意义
    const sub = await NativeFiles.listFolder({ path: entry.path });
    console.log(`${entry.name}/`, sub.entries.map((e) => e.name));
  } else if (entry.name.endsWith('.md')) {
    const { base64 } = await NativeFiles.readFile({ path: entry.path });
    const text = atob(base64);                 // 纯 ASCII 内容；中文见下方说明
    console.log(entry.name, text.length);
  }
}

// 新建/重命名/删除
await NativeFiles.createFolder({ path: `${root}/drafts/2026` });
await NativeFiles.renameFolder({ path: `${root}/drafts`, newName: 'drafts-old' });
await NativeFiles.deleteFolder({ path: `${root}/drafts-old`, recursive: true });
```

> **非 ASCII 文本**：`readFile` 返回 base64，`atob` 得到的是二进制字符串。
> 读 UTF-8 文本请用：
>
> ```ts
> const bytes = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
> const text = new TextDecoder('utf-8').decode(bytes);
> ```
>
> 写文本反过来：`btoa(String.fromCharCode(...new TextEncoder().encode(text)))`（大文本请分块）。

### 2) 选文件（多选）

```ts
const { files, cancelled, unmapped } = await NativeFiles.pickFiles({
  multiple: true,
  mimeTypes: ['text/markdown', 'text/plain'],
});
// files: [{ path, name, size, lastModified, mimeType }]
if (unmapped?.length) {
  console.warn('这些来源没有真实路径（云盘等），已跳过：', unmapped);
}
```

### 3) 读写文件

```ts
// 写（父目录会自动创建）
await NativeFiles.writeFile({
  path: '/storage/emulated/0/Documents/hello.md',
  base64: btoa('# hello'),
  mode: 'overwrite',      // create | overwrite | append
});

// 读（base64；单次最多 16MB）
const { base64, bytes, size } = await NativeFiles.readFile({ path: '.../hello.md' });

// 分块读大文件
const chunk = await NativeFiles.readFile({ path, offset: 0, length: 1024 * 1024 });

// 删除（不可恢复）
await NativeFiles.deleteFile({ path: '.../hello.md' });
```

### 4) 文件/目录信息（对应 data-channel 原来的 `GET /info`）

```ts
const info = await NativeFiles.getInfo({ path: '/storage/emulated/0/Notes/a.md' });
// { path, name, exists, isDirectory, isFile, size, lastModified, readable, writable, mimeType }
```

### 5) 申请"所有文件访问权限"（Android 11+）

```ts
// 先查
const status = await NativeFiles.checkAllFilesAccess();
// { granted, androidVersion, requiresSettings, permission }

// 再申请（Android 11+ 打开系统设置页；≤10 弹运行时权限；iOS 返回 applicable:false）
const result = await NativeFiles.requestAllFilesAccess();
console.log('granted:', result.granted);
```

> 没有该权限时，读写公共目录会 reject，错误信息里带
> `Grant "All files access" via NativeFiles.requestAllFilesAccess() first.` 提示。

### 6) 与 data-channel 配合

data-channel 的 `rootDir` 支持**绝对路径**，所以不用先复制文件：

```ts
// 1) 选文件/选目录（拿到绝对路径）
const { files } = await NativeFiles.pickFiles({ multiple: false });
const { folders } = await NativeFiles.pickFolders();

// 2) 直接把 data-channel 的根目录指到该文件/目录所在位置
const http = await DataChannel.startHttp({ rootDir: folders[0].path });
//    或者：rootDir 用文件所在目录，path 用文件名
const otp = await DataChannel.createHttpOtp({ scope: 'write', path: 'voice.mp3' });
await fetch(`${http.baseUrl}/upload?path=voice.mp3`, {
  method: 'PUT',
  headers: { Authorization: `OTP ${otp.otp}` },
  body: fileBlob,
});
```

> data-channel 的 `path` 始终是**相对 `rootDir`** 的路径（且有 `..`/软链逃逸校验）；
> 要访问哪个目录，就把 `rootDir` 指到那里。因此本插件**不再提供** `copyToAppStorage`
> （0.3.0 删除）——两个插件各自只做自己该做的事。
>
> **Android 11+ 注意**：`rootDir` 指向公共目录时需要"所有文件访问权限"（先 `requestAllFilesAccess()`）。

### 7) 在 WebView 里直接读文件（URL 方案完整指南）

> 这一节回答一个实际需求：**"我不想把文件内容 base64 过桥，能不能让 WebView 直接按 URL 读文件？"**
> 能，而且有三种做法。下面先说结论，再给完整示例、平台差异和**实测踩到的坑**。

#### 7.1 三条路线怎么选

| 方案 | 要写原生代码？ | 上传 | WebSocket | Range（视频/PDF 拖动） | 鉴权 | 适合 |
| --- | --- | --- | --- | --- | --- | --- |
| **A. `Capacitor.convertFileSrc()`**（内置 `_capacitor_file_`） | ❌ 不用 | ❌ | ❌ | ⚠️ 有 bug（见 7.5） | 无（同源，页面里任何脚本都能读） | **图片/文本/音频/脚本预览** |
| **B. 自定义 scheme**（`appfiles://` 或 https 虚拟域名） | ✅ 要 | ❌ | ❌ | ✅ 自己实现就准 | 自己加（路径白名单） | 需要精确 Range、不想开端口 |
| **C. 本地 HTTP**（`capacitor-data-channel`） | ✅ 用现成插件 | ✅ | ✅ | ✅ 已实现 | ✅ 随机端口 + OTP + Origin 白名单 | 上传/下载/实时/大文件 |

一句话：**只读展示优先 A；要精确 Range 用 B 或 C；要上传/双向/实时用 C。**

---

#### 7.2 方案 A：`Capacitor.convertFileSrc()`（推荐，零原生代码）

```ts
import { Capacitor } from '@capacitor/core';
import { NativeFiles } from 'capacitor-native-files';

const urlOf = (absolutePath: string) => Capacitor.convertFileSrc(absolutePath);

// 图片
const img = document.querySelector('img')!;
img.src = urlOf('/storage/emulated/0/Notes/images/cover.png');

// 音频 / 视频（内联播放）
const audio = new Audio(urlOf('/storage/emulated/0/Notes/audio/01.mp3'));
audio.play();

// 文本 / JSON / 二进制
const text = await (await fetch(urlOf('/storage/emulated/0/Notes/a.md'))).text();
const json = await (await fetch(urlOf('/storage/emulated/0/Notes/meta.json'))).json();
const bytes = new Uint8Array(await (await fetch(urlOf(...))).arrayBuffer());

// PDF（iframe 内联预览；分页跳转依赖 Range，见 7.5 的坑）
pdfFrame.src = urlOf('/storage/emulated/0/Documents/paper.pdf');
```

生成的 URL 与页面**同源**，所以**不需要 CORS**、不需要 `network_security_config`：

| 平台 | URL 形态 |
| --- | --- |
| Android | `https://localhost/_capacitor_file_/storage/emulated/0/Notes/cover.png` |
| iOS | `capacitor://localhost/_capacitor_file_/storage/emulated/0/Notes/cover.png` |

**Markdown 阅读器的完整例子**（列目录 → 拼路径 → 懒加载图片 / 读正文 / 打开附件）：

```ts
import { Capacitor } from '@capacitor/core';
import { NativeFiles } from 'capacitor-native-files';

async function renderBook(bookDir: string) {
  // 1) 列出书的目录（路径可以直接拼）
  const { entries } = await NativeFiles.listFolder({ path: bookDir });

  // 2) 正文：读成字符串
  const mdEntry = entries.find((e) => !e.isDirectory && e.name.endsWith('.md'));
  if (mdEntry) {
    const md = await (await fetch(Capacitor.convertFileSrc(mdEntry.path))).text();
    renderMarkdown(md);
  }

  // 3) 图片：先用 getInfo 拿尺寸信息（可选），再懒加载
  const images = entries.filter((e) => !e.isDirectory && /\.(png|jpe?g|webp|gif)$/i.test(e.name));
  for (const image of images) {
    const el = document.createElement('img');
    el.loading = 'lazy';
    el.src = Capacitor.convertFileSrc(image.path);   // 直接交给 WebView 解码，不过桥
    el.alt = image.name;
    imageContainer.appendChild(el);
  }

  // 4) 大附件：流式读取（避免把整个文件读进内存）
  const big = entries.find((e) => !e.isDirectory && e.name.endsWith('.mp4'));
  if (big) {
    const res = await fetch(Capacitor.convertFileSrc(big.path));
    const reader = res.body!.getReader();
    let total = 0;
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;      // 可以在这里做进度条 / 分片处理
      consume(value);
    }
    console.log('streamed bytes', total);
  }
}
```

URL Scheme 方式的其他示例:

```
/**
  * 准备两个测试文件（文本 + 1x1 PNG），放在"公共 Documents"（拿不到就退回 App 文档目录）。
  * 返回真实绝对路径，供 convertFileSrc 转换。
  */
async urlTestFiles() {
  const shared = await NativeFiles.getSharedDocumentsDir();
  let base = shared.path;
  if (!base) {
    const appDocs = await NativeFiles.getAppDocumentsDir();
    base = appDocs.path;
  }
  if (!base) {
    throw new Error('no writable docs directory');
  }
  await NativeFiles.createFolder({ path: base });

  const txtPath = `${base}/nf-url-test.txt`;
  const pngPath = `${base}/nf-url-test.png`;
  const jsPath = `${base}/nf-url-test.js`;

  await NativeFiles.writeFile({
    path: txtPath,
    base64: btoa('hello-from-file-url-0123456789'),
    mode: 'overwrite',
  });
  // 1x1 红色 PNG
  const pngBase64 =
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';
  await NativeFiles.writeFile({ path: pngPath, base64: pngBase64, mode: 'overwrite' });
  await NativeFiles.writeFile({
    path: jsPath,
    base64: btoa("window.__nfUrlScriptLoaded = 'yes';"),
    mode: 'overwrite',
  });

  return { base, txtPath, pngPath, jsPath };
},

async urlSchemeFetch() {
  try {
    const { txtPath } = await this.urlTestFiles();
    const url = Capacitor.convertFileSrc(txtPath);
    const res = await fetch(url);
    const text = await res.text();
    this.print('文件 URL fetch 成功', {
      url,
      status: res.status,
      contentType: res.headers.get('Content-Type'),
      contentLength: res.headers.get('Content-Length'),
      acceptRanges: res.headers.get('Accept-Ranges'),
      body: text,
    });
  } catch (err) {
    this.error('文件 URL fetch', err);
  }
},

async urlSchemeRange() {
  try {
    const { txtPath } = await this.urlTestFiles();
    const url = Capacitor.convertFileSrc(txtPath);
    const res = await fetch(url, { headers: { Range: 'bytes=6-17' } });
    const text = await res.text();
    this.print('文件 URL Range 请求', {
      status: res.status,
      contentRange: res.headers.get('Content-Range'),
      body: text,
    });
  } catch (err) {
    this.error('文件 URL Range', err);
  }
},

async urlSchemeImage() {
  try {
    const { pngPath } = await this.urlTestFiles();
    const url = Capacitor.convertFileSrc(pngPath);
    const loaded = await new Promise((resolve) => {
      const img = new Image();
      img.onload = () => resolve({ ok: true, width: img.naturalWidth, height: img.naturalHeight });
      img.onerror = () => resolve({ ok: false });
      img.src = url;
    });
    this.urlPreview = url;
    this.print('文件 URL 图片加载', { url, ...loaded });
  } catch (err) {
    this.error('文件 URL 图片', err);
  }
},

async urlSchemeScript() {
  try {
    const { jsPath } = await this.urlTestFiles();
    const url = Capacitor.convertFileSrc(jsPath);
    delete window.__nfUrlScriptLoaded;
    const loaded = await new Promise((resolve) => {
      const script = document.createElement('script');
      script.src = url;
      script.onload = () => resolve(true);
      script.onerror = () => resolve(false);
      document.head.appendChild(script);
    });
    this.print('文件 URL 动态 script', { url, loaded, marker: window.__nfUrlScriptLoaded });
  } catch (err) {
    this.error('文件 URL script', err);
  }
},

/** 一键自检：给按钮和 autotest 共用。 */
async urlSchemeSelfTest() {
  const { base, txtPath, pngPath, jsPath } = await this.urlTestFiles();
  const txtUrl = Capacitor.convertFileSrc(txtPath);
  const pngUrl = Capacitor.convertFileSrc(pngPath);
  const jsUrl = Capacitor.convertFileSrc(jsPath);

  const plain = await fetch(txtUrl);
  const plainText = await plain.text();

  const range = await fetch(txtUrl, { headers: { Range: 'bytes=6-17' } });
  const rangeText = await range.text();

  const image = await new Promise((resolve) => {
    const img = new Image();
    img.onload = () => resolve({ ok: true, width: img.naturalWidth, height: img.naturalHeight });
    img.onerror = () => resolve({ ok: false });
    img.src = pngUrl;
  });

  delete window.__nfUrlScriptLoaded;
  const scriptLoaded = await new Promise((resolve) => {
    const script = document.createElement('script');
    script.src = jsUrl;
    script.onload = () => resolve(true);
    script.onerror = () => resolve(false);
    document.head.appendChild(script);
  });

  return {
    base,
    txtUrl,
    fetchOk: plain.status === 200 && plainText === 'hello-from-file-url-0123456789',
    fetchStatus: plain.status,
    contentType: plain.headers.get('Content-Type'),
    acceptRanges: plain.headers.get('Accept-Ranges'),
    rangeStatus: range.status,
    rangeContentRange: range.headers.get('Content-Range'),
    rangeOk: rangeText === plainText.slice(6, 18),
    rangeBody: rangeText,
    // Capacitor 8.6.0 的内置 Range 实现有 bug：Content-Range 写对了，但正文
    // 会把 start 之后的内容全部发回来（忽略 end）。这里如实记录，避免误判。
    rangeEndIgnored: rangeText.length > 12,
    imageOk: image.ok,
    imageWidth: image.width || 0,
    scriptOk: scriptLoaded === true && window.__nfUrlScriptLoaded === 'yes',
  };
},

async urlSchemeAll() {
  try {
    const result = await this.urlSchemeSelfTest();
    this.print('文件 URL 一键自检', result);
  } catch (err) {
    this.error('文件 URL 自检', err);
  }
},

async urlSchemeCleanup() {
  try {
    const { base } = await this.urlTestFiles();
    for (const name of ['nf-url-test.txt', 'nf-url-test.png', 'nf-url-test.js']) {
      await NativeFiles.deleteFile({ path: `${base}/${name}` }).catch(() => {});
    }
    this.urlPreview = '';
    this.print('文件 URL 测试文件已清理', { base });
  } catch (err) {
    this.error('文件 URL 清理', err);
  }
},
```

URL Scheme 是什么?

```
URL scheme 是什么（原理）
WebView 里 JS 发起的每个请求（fetch、<img src>、<video src>、<script>、CSS url()…）都会经过原生层的"请求拦截器"。URL scheme 方案就是：自定义一个协议（如 appfiles://），让这些请求被原生代码接住，原生直接读文件并把字节流还给 WebView。

关键点：数据不经过 Capacitor 桥（不用 base64、不用 JSON 序列化），WebView 自己按需流式读取——这才是它能处理大文件的原因。

代码块

JS: fetch('appfiles://media/storage/emulated/0/Notes/a.md')
        │
        ▼
WebView 内核 → 原生拦截器（Android: shouldInterceptRequest / iOS: WKURLSchemeHandler）
        │
        ├─ 解析 URL → 真实文件路径（要自己做白名单校验！）
        ├─ 打开文件流（可 Range 定位）
        └─ 构造响应（Content-Type / Content-Length / Accept-Ranges / CORS 头）
        │
        ▼
WebView 拿到流式响应 → JS 的 fetch 得到 Response / <video> 直接边下边播

2.1 Android 实现
拦截入口是 WebViewClient.shouldInterceptRequest（后台 IO 线程，不要阻塞太久）：

java

webView.setWebViewClient(new WebViewClient() {
  @Override
  public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
    Uri uri = req.getUrl();
    if (!"appfiles".equals(uri.getScheme())) {
      return null;              // 其它请求走正常网络
    }
    File file = resolveSafely(uri);          // 自定义：appfiles://media/<绝对路径> → File + 白名单校验
    if (file == null || !file.isFile()) {
      return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
          Map.of(), new ByteArrayInputStream("not found".getBytes()));
    }

    Map<String, String> headers = new HashMap<>();
    headers.put("Content-Type", guessMime(file));
    headers.put("Accept-Ranges", "bytes");
    headers.put("Access-Control-Allow-Origin", "*");   // 页面 origin 是 https://localhost，属于跨域

    // Range 支持（<video>/<audio> 拖动进度条必需）
    long start = 0, end = file.length() - 1;
    String range = req.getRequestHeaders().get("Range");
    if (range != null && range.startsWith("bytes=")) { /* 解析 start-end */ }

    try {
      FileInputStream in = new FileInputStream(file);
      in.skip(start);
      long length = end - start + 1;
      headers.put("Content-Range", "bytes " + start + "-" + end + "/" + file.length());
      return new WebResourceResponse(guessMime(file), null,
          range != null ? 206 : 200, range != null ? "Partial Content" : "OK",
          headers, new LimitedInputStream(in, length));
    } catch (Exception e) {
      return null;
    }
  }
});

Android 还有一条官方路线：WebViewAssetLoader（把 https://appassets.androidplatform.net/... 映射到本地目录）， 好处是走 https 所以不用处理自定义 scheme 的各种兼容问题，也支持 Range（它内部实现了）。

2.2 iOS 实现

自定义 scheme 必须用 WKURLSchemeHandler，而且只能在创建 WKWebView 之前注册：

swift

final class FileSchemeHandler: NSObject, WKURLSchemeHandler {
  func webView(_ webView: WKWebView, start task: WKURLSchemeTask) {
    guard let url = task.request.url, let file = resolveSafely(url) else { return }
    let size = (try? FileManager.default.attributesOfItem(atPath: file.path)[.size] as? Int64) ?? 0
    let headers = [
      "Content-Type": mime(file),
      "Content-Length": "\(size)",
      "Accept-Ranges": "bytes",
      "Access-Control-Allow-Origin": "*",
    ]
    let response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: headers)!
    task.didReceive(response)

    // 分块喂数据；大文件千万不要一次读进内存
    let handle = try! FileHandle(forReadingFrom: file)
    while true {
      let chunk = try! handle.read(upToCount: 256 * 1024) ?? Data()
      if chunk.isEmpty { break }
      task.didReceive(chunk)
    }
    try? handle.close()
    task.didFinish()
  }

  func webView(_ webView: WKWebView, stop task: WKURLSchemeTask) { /* 取消读取 */ }
}

let config = WKWebViewConfiguration()
config.setURLSchemeHandler(FileSchemeHandler(), forURLScheme: "appfiles")
let webView = WKWebView(frame: .zero, configuration: config)

iOS 的两个硬约束：

setURLSchemeHandler(_:forURLScheme: "http") / "https" 会直接抛异常（系统不允许抢占标准协议）；
因此 Capacitor（它自己创建 WKWebView）里纯插件无法注册 scheme handler——必须由宿主 App 提供自定义 CAPBridgeViewController（或改 Capacitor 的 webview 创建流程）。这是"scheme 方案在 iOS 上天生更适合 App 层，而非插件层"的原因。

2.3 JS 侧怎么用

js

// 1) 直接当作普通 URL
const res = await fetch('appfiles://media/storage/emulated/0/Notes/big.pdf');
const buf = await res.arrayBuffer();          // 小文件；大文件用 res.body 流式读
const reader = res.body.getReader();          // 流式处理，内存占用恒定

// 2) 让浏览器内核自己流式解码（零拷贝、可拖动进度）
video.src = 'appfiles://media/storage/emulated/0/Movies/demo.mp4';
img.src   = 'appfiles://media/storage/emulated/0/Pictures/a.jpg';
pdfIframe.src = 'appfiles://media/storage/emulated/0/Documents/paper.pdf';

// 3) Range 请求（断点/分段）
await fetch('appfiles://media/.../big.bin', { headers: { Range: 'bytes=1048576-2097151' } });
```

能不能拿 URL Scheme 代替 data-channel?

```
# 一、URL Scheme 本质就是 WebView 的请求拦截器

而且 **Capacitor 自己就是靠它工作的**：

| 平台 | 入口 | 证据 |
|---|---|---|
| Android | `WebViewClient.shouldInterceptRequest` | `BridgeWebViewClient.shouldInterceptRequest()` → `bridge.getLocalServer().shouldInterceptRequest(request)`，内部再分发 `_capacitor_file_` / `_capacitor_content_` / `_capacitor_http_interceptor_` |
| iOS | `WKURLSchemeHandler` | `WebViewAssetHandler`（Capacitor 在创建 WKWebView 时就注册给 `capacitor://`），同样是 `_capacitor_file_` / `_capacitor_http_interceptor_` |

所以 `Capacitor.convertFileSrc()` 能读文件，不是因为有个"文件插件"，而是**内核注册的拦截器**把 `capacitor://localhost/_capacitor_file_/...` 映射成了文件流。

# 二、但它是**单向**的：能"喂"数据给 WebView，拿不到"WebView 发出去的请求体"

这是最容易误解的地方，也决定了它替代不了 data-channel。

## 响应方向（native → WebView）：完全可控 ✅

你可以返回任意状态码、头、二进制流：

java

// Android：读文件 → 直接作为响应体返回（这就是我们的实测）
return new WebResourceResponse(mime, null, 206, "Partial Content", headers, fileInputStream);

所以 `<img>`、`<video>`、`fetch().text()`、`arrayBuffer()` 都能拿到**二进制数据**——这就是"读取外部数据"的本质。

## 请求方向（WebView → native）：拿不到 body ❌

| 平台 | 拦截器能拿到什么 |
|---|---|
| **Android** | `WebResourceRequest` 只有 `getUrl()` / `getMethod()` / `getRequestHeaders()` / `isForMainFrame()` —— **API 上就没有 body**。POST 再大也读不到 |
| **iOS** | `WKURLSchemeTask.request` 是个 `URLRequest`，**理论上有 `httpBody`，但 WebKit 对页面发起的 fetch/XHR 是否填充并不保证**，不能依赖 |

也就是说：

- **读/下载方向**：scheme 拦截基本等价于 HTTP GET（甚至更简单：同源、无端口、无 CORS）；
- **写/上传方向**：**做不到**。你想"把文件传进去"，只能把数据塞进 URL（长度有限、极丑），或者老老实实走桥 / 本地 HTTP。

> 补充一个容易混淆的点：Capacitor 的 `CapacitorHttp`（patched fetch/XHR）也有"上传"能力，但它**不是靠拦截请求体**——它在 JS 层把请求改成 native 调用、body 走桥传递，再由 native 发真请求。这属于"插件层 patch"，不是"拦截器读 body"。

# 三、和 data-channel 的能力对比（如实）

| 能力 | scheme 拦截 | 本地 HTTP（data-channel） |
|---|---|---|
| 读文件 / 下载 | ✅ 流式、无端口、同源（`_capacitor_file_`） | ✅ 流式（需随机端口） |
| **上传（PUT/POST body）** | ❌ 拿不到请求体 | ✅ 完整实现 |
| **WebSocket / 实时推送** | ❌ 没有这个协议 | ✅ `ws://127.0.0.1:port/ws` |
| 双向（native 主动通知 WebView） | ❌ 只能靠桥事件 | ✅ WS 推送 |
| 精确 Range（视频拖动 / PDF 翻页） | ✅ **自己实现就准**（Capacitor 自带那版有 bug） | ✅ 已实现 |
| 断点续传 / sha256 校验 | 要自己做（拦截器里能算） | ✅ 已有 |
| 鉴权 | ⚠️ 弱：同源页面里任何脚本都能读（无 token 概念） | ✅ 端口 + OTP + Origin 白名单 |
| 需要放行明文回环 | ❌ 不需要 | ✅ 需要 `network_security_config` |
| 首字节延迟 | 更低（没有 socket 握手） | 稍高（127.0.0.1 回环） |
| 后台存活 | 跟 WebView 生命周期 | socket 会被系统挂起 |
| 插件化 | Android ✅ / **iOS ❌（见下）** | 两端 ✅ |

结论：**它 ≈ data-channel 的"HTTP GET/读"那半，不含"写/上传/实时"那半。**

# 四、"做不成插件"的准确说法

要分平台，我之前的表述太笼统了：

## Android：**可以做成插件** ✅

我们其实已经写过一份（`SchemeTestPlugin.java`，现在放在 `.perf/SchemeTestPlugin.java.bak`）：

- 在 `register()` 里拿到 `webView`，把 `WebViewClient` 换成 **`BridgeWebViewClient` 的子类**（只重写 `shouldInterceptRequest`，其它行为完全保留）；
- `unregister()` 再换回 `new BridgeWebViewClient(bridge)`。

Capacitor 自己也是这么装的，所以 Android 完全可行。

## iOS：**纯插件做不到** ❌（这是硬限制）

原因在时序上：

CAPBridgeViewController.viewDidLoad()
   └─ 创建 WKWebView 前，先调 webViewConfiguration(for:)  →  这里注册 capacitor:// handler
   └─ new WKWebView(frame:configuration:)                  →  配置一旦生效就冻结
   └─ bridge 建立 → 插件的 load() 才被调用                   ← 插件此时拿到 webView 已经晚了

`WKURLSchemeHandler` **只能挂在 `WKWebViewConfiguration` 上，且必须在 WebView 创建前**。插件在 `load()` 里拿到的 `bridge.webView` 已经创建完成，没法追加 handler（也不能重建 WebView —— 会丢状态、重载页面）。

另外两个相关限制：
1. **`http` / `https` 不能注册**（`setURLSchemeHandler(forURLScheme: "https")` 直接抛异常），所以 Android 那种"https 虚拟域名让 fetch 可用"的技巧在 iOS 上没有对应物；
2. `capacitor://` 已被 Capacitor 占用，别注册同名。

## 那 iOS 上怎么落地？三种现实做法

| 做法 | 说明 |
|---|---|
| **A. 宿主 App 子类化 `CAPBridgeViewController`** | 重写 `webViewConfiguration(for:)` 注册你的 handler（README 7.3 已给完整代码），`SceneDelegate` 里改用这个子类。**这是唯一可靠方式** |
| **B. 插件只提供实现类，注册交给宿主** | 插件可以 ship `FileSchemeHandler` + 一行注册说明，但"注册"这一步必须写在 App 里（本质还是 A） |
| **C. 用 Capacitor 内建的 `_capacitor_file_`** | 就是 `convertFileSrc()`——只读够用，零代码，但 Range 有 bug、无法上传 |

这也解释了为什么我在文档里说"scheme 在 iOS 上天生更适合 App 层而不是插件层"——不是技术不可行，是**注册时机只存在于 App 启动阶段**。

# 五、一句话总结

- **机制**：是的，就是 WebView 的请求拦截器（Android `shouldInterceptRequest` / iOS `WKURLSchemeHandler`），Capacitor 内核自己就在用；
- **能力**：只能"读出去"（native → WebView，可流式、可二进制、可自定 Range），**不能"写进来"**（拿不到请求体）；
- **替代 data-channel**：只读/下载那半可以（而且更轻），上传 + WebSocket + 鉴权 + 精确 Range 那半不行；
- **插件化**：Android 能（可参考 `.perf` 里那份实现），iOS 必须由宿主 App 在创建 WebView 时注册。
```

**注意事项（A 方案）**：

1. **必须传绝对路径**（`/storage/emulated/0/...`）。传相对路径、`content://` 都不行。
2. **特殊字符要转义**（实测）：
   - 中文、空格 → 直接用没问题（`中文 空格 test.md` 实测 200）；
   - 文件名含 **`#`** → 会被当成 fragment，**404**；含 **`%`** → 也会 404。
     解决办法：**按路径段 `encodeURIComponent`**（业务代码里最好统一这么做）：

   ```ts
   // 保留协议/主机，逐段编码路径；中文/空格用不用它都行，但 # 和 % 必须编码
   const toFileUrl = (absPath: string) => {
     const base = Capacitor.convertFileSrc('/').replace(/\/+$/, ''); // https://localhost/_capacitor_file_
     const encoded = absPath.split('/').map(encodeURIComponent).join('/');
     return base + encoded;
   };

   // 实测：hash#1.md → hash%231.md、pct%41.md → pct%2541.md，都返回 200
   ```
3. **`?` 在共享存储上根本建不出来**（`EPERM`），所以不用担心；但代码里做转义更稳。
4. **没有路径白名单**：`_capacitor_file_` 会读取 App 有权访问的**任意**路径。
   页面一旦被 XSS，攻击者就能 `fetch` 整个可访问目录 → 只读展示也要注意页面内容可信。
5. **Range 有 bug**（见 7.5）→ 视频拖动 / PDF 翻页不可靠。

---

#### 7.3 方案 B：自定义 scheme（需要写原生；能精确控制 Range）

**Android 两种写法**

1. **自定义 scheme**（`appfiles://local/<绝对路径>`）——拦截 `shouldInterceptRequest`；
2. **https 虚拟域名**（`https://appassets.androidplatform.net/<绝对路径>`）——同样拦截，但因为是 `https`，
   `fetch`/XHR 不会被 Chromium 的 scheme 限制挡住（推荐）。

```java
// 关键点：必须继承 Capacitor 的 BridgeWebViewClient，否则会破坏桥通信
class FileClient extends BridgeWebViewClient {
  FileClient(Bridge bridge) { super(bridge); }

  @Override
  public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
    Uri uri = request.getUrl();
    boolean custom = "appfiles".equals(uri.getScheme());
    boolean https = "https".equals(uri.getScheme())
        && "appassets.androidplatform.net".equals(uri.getHost());
    if (!custom && !https) {
      return super.shouldInterceptRequest(view, request);
    }

    File file = new File(Uri.decode(uri.getPath()));
    if (!isInsideAllowedRoots(file)) {                 // ← 一定要做白名单！
      return new WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", cors(), null);
    }
    if (!file.isFile()) {
      return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", cors(), null);
    }

    // Range：解析 bytes=start-end（视频拖动必需）
    long total = file.length(), start = 0, end = total - 1;
    boolean partial = false;
    String range = request.getRequestHeaders().get("Range");
    if (range != null && range.startsWith("bytes=")) {
      String[] parts = range.substring(6).split("-");
      start = Long.parseLong(parts[0]);
      if (parts.length > 1 && !parts[1].isEmpty()) end = Math.min(Long.parseLong(parts[1]), total - 1);
      partial = true;
    }
    long length = end - start + 1;

    Map<String, String> headers = cors();
    headers.put("Accept-Ranges", "bytes");
    headers.put("Content-Length", String.valueOf(length));
    headers.put("Cache-Control", "no-store");
    if (partial) headers.put("Content-Range", "bytes " + start + "-" + end + "/" + total);

    try {
      FileInputStream in = new FileInputStream(file);
      in.skip(start);
      WebResourceResponse response = new WebResourceResponse(
          guessMime(file.getName()), null,
          partial ? 206 : 200, partial ? "Partial Content" : "OK",
          headers, new LimitedStream(in, length));   // 只读 length 字节，别把文件全暴露
      return response;
    } catch (Exception e) {
      return new WebResourceResponse("text/plain", "utf-8", 500, "Error", cors(), null);
    }
  }

  private Map<String, String> cors() {
    Map<String, String> h = new HashMap<>();
    h.put("Access-Control-Allow-Origin", "*");
    h.put("Access-Control-Allow-Headers", "Range");
    h.put("Access-Control-Expose-Headers", "Content-Length,Content-Range,Accept-Ranges");
    return h;
  }
}
```

> `LimitedStream` 是一个只允许读 `length` 字节的 `FileInputStream` 包装（见插件源码或自己实现），
> 否则 `Content-Length` 与实际字节数不一致，媒体播放会出错。

**iOS：必须在"创建 WKWebView 之前"注册**

```swift
final class FileSchemeHandler: NSObject, WKURLSchemeHandler {
  func webView(_ webView: WKWebView, start task: WKURLSchemeTask) {
    guard let url = task.request.url, let file = allowedFile(for: url) else { return }
    let size = (try? FileManager.default.attributesOfItem(atPath: file.path)[.size] as? Int64) ?? 0

    // Range 同理：解析 task.request.value(forHTTPHeaderField: "Range")，
    // 返回 206 + Content-Range，并用 FileHandle.seek 定位
    let headers = [
      "Content-Type": mime(file),
      "Content-Length": "\(size)",
      "Accept-Ranges": "bytes",
      "Access-Control-Allow-Origin": "*",
    ]
    task.didReceive(HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: headers)!)
    let handle = try? FileHandle(forReadingFrom: file)
    while let chunk = try? handle?.read(upToCount: 256 * 1024), let data = chunk, !data.isEmpty {
      task.didReceive(data)
    }
    try? handle?.close()
    task.didFinish()
  }

  func webView(_ webView: WKWebView, stop task: WKURLSchemeTask) { /* 取消读取 */ }
}
```

Capacitor 里注册它需要**自定义 ViewController**（纯插件做不到）：

```swift
class SchemeViewController: CAPBridgeViewController {
  override func webViewConfiguration(for instanceConfiguration: InstanceConfiguration) -> WKWebViewConfiguration {
    let config = super.webViewConfiguration(for: instanceConfiguration)
    config.setURLSchemeHandler(FileSchemeHandler(), forURLScheme: "appfiles")
    return config
  }
}
// SceneDelegate 里把 rootViewController 换成 SchemeViewController()
```

**注意事项（B 方案）**：

1. **iOS 的 `http`/`https` 不能注册**（`setURLSchemeHandler` 会抛异常），只能用自定义 scheme；
   而 Capacitor 自己已经占用了 `capacitor://`，别再注册同名。
2. **`fetch('appfiles://...')` 很可能直接 TypeError**：Chromium 的 Fetch 规范只允许
   `http/https/data/blob`。自定义 scheme 通常只对 **`<img>` / `<video>` / `<script>` / CSS** 这类子资源有效；
   想在 JS 里 `fetch` 就用 **https 虚拟域名**（Android 的 `WebViewAssetLoader` 方案）。
3. 拦截发生在 **IO 线程**，不要做重活；文件不存在时**返回 null 会让 WebView 走网络**，记得显式返回错误响应。
4. 一定要做**路径白名单**（`canonicalPath` 前缀校验），否则等于把整个磁盘暴露给页面。

---

#### 7.4 方案 C：本地 HTTP（`capacitor-data-channel`）

需要**上传、WebSocket、可续传、精确 Range、鉴权**时用它，不要自己写 scheme：

```ts
const http = await DataChannel.startHttp({ rootDir: '/storage/emulated/0/Documents' });
const otp = await DataChannel.createHttpOtp({ scope: 'read', path: 'paper.pdf' });

// 整文件
const res = await fetch(`${http.baseUrl}/download?path=paper.pdf`, {
  headers: { Authorization: `OTP ${otp.otp}` },
});

// 精确 Range（data-channel 有完整 206 实现）
const part = await fetch(`${http.baseUrl}/download?path=paper.pdf`, {
  headers: { Authorization: `OTP ${otp.otp}`, Range: 'bytes=0-1023' },
});
```

---

#### 7.5 Capacitor 当前的坑（实测，Capacitor 8.6.0）

| # | 坑 | 现象 | 规避 |
| --- | --- | --- | --- |
| 1 | **`_capacitor_file_` 的 Range 实现有 bug** | 请求 `bytes=6-17`（期望 12 字节）时，响应头 `Content-Range: bytes 6-17/30` 正确，但**正文返回 start 之后的全部 23 字节** | 不要依赖它做视频拖动/PDF 翻页；用 data-channel 或自写 handler |
| 2 | **不返回 `Accept-Ranges`** | 前端 `response.headers.get('Accept-Ranges') === null` | 别据此判断是否支持 Range |
| 3 | **文件名含 `#` / `%` 会 404** | `hash#1.md` 被当成 fragment；`pct%41.md` 被当成转义序列 | 按路径段 `encodeURIComponent`（中文/空格无需处理） |
| 4 | **`_capacitor_file_` 无路径白名单** | 页面能读 App 有权限的任意路径 | 页面内容可信；或改用方案 B/C 自己加白名单 |
| 5 | **iOS 不能注册 `http(s)` scheme** | `setURLSchemeHandler(forURLScheme: "https")` 抛异常 | iOS 用自定义 scheme（需宿主改 VC 配置） |
| 6 | **`fetch` 到自定义 scheme 在 Chromium 上受限** | `fetch('appfiles://...')` TypeError | 用 https 虚拟域名（Android）或本地 HTTP |
| 7 | **LocalHTTP 需要明文回环放行** | 页面 `https://localhost` 访问 `http://127.0.0.1` 被拒 | 宿主加 `network_security_config.xml`（见 data-channel README） |

> 关于坑 1 的源码位置：`node_modules/@capacitor/android/capacitor/src/main/java/com/getcapacitor/WebViewLocalServer.java`
> 的 range 分支——它只 `skip(start)` 而没有按 `end` 限长（方法本身也标了 `@deprecated ... may return incorrect headers`）。
>
> 关于 iOS：`capacitor://localhost/_capacitor_file_/...` 的 `fetch` / `<img>` / `<script>` 已由 iOS CI 的 autotest 验证通过；
> **Range 行为未单独断言**，需要精确 Range 时同样建议用 data-channel。

还有一个 Capacitor.js 自己的坑:
...文档的示例里写的是 http://localhost/_capacitor_file_/... ，但实测（Capacitor 8.6.0）生成的是 https://localhost/_capacitor_file_/... （iOS 是 capacitor://localhost/_capacitor_file_/...）。以实际输出为准——我们 README 里记的是实测值。
---

#### 7.6 一句话决策

- 只是**看图/读文本/放音频** → `Capacitor.convertFileSrc()`（零代码，见 7.2）；
- 要**视频拖动、PDF 翻页、精确分段** → `capacitor-data-channel` 的 HTTP（7.4）；
- 既不想开端口、又要精确 Range、且能接受写原生 → 自定义 scheme（7.3，注意平台限制）。

## API 说明

| 方法 | 参数 | 返回 |
| --- | --- | --- |
| `pickFiles` | `{ multiple?, mimeTypes? }` | `{ files: FileEntry[], cancelled, unmapped?, warning? }` |
| `pickFolders` | — | `{ folders: DirEntry[], cancelled }` |
| `readFile` | `{ path, offset?, length? }` | `{ path, base64, bytes, size, offset }` |
| `writeFile` | `{ path, base64, mode? }` | `{ path, bytes, size }` |
| `deleteFile` | `{ path }` | `{ deleted, reason? }` |
| `listFolder` | `{ path, recursive?, maxEntries? }` | `{ path, entries: FsEntry[], truncated }` |
| `createFolder` | `{ path }` | `{ path, created }` |
| `deleteFolder` | `{ path, recursive? }` | `{ deleted, reason?, hint? }` |
| `renameFolder` | `{ path, newName }` | `{ renamed, path, from }` |
| `getInfo` | `{ path }` | `PathInfo` |
| `checkAllFilesAccess` | — | `{ granted, androidVersion, requiresSettings, permission }` |
| `requestAllFilesAccess` | — | `{ granted, androidVersion }` / `{ granted, applicable: false, message }` |
| 23 个目录 API | — | `{ path: string \| null, reason?: string }` |

目录 API 共 23 个：`getAppFilesDir` / `getAppCacheDir` / `getAppTempDir` / `getAppExternalFilesDir` /
`getAppExternalCacheDir` / `getAppDocumentsDir` / `getSharedStorageDir` / `getDownloadsDir` /
`getSharedDocumentsDir` / `getPicturesDir` / `getDcimDir` / `getMoviesDir` / `getMusicDir` /
`getAlarmsDir` / `getNotificationsDir` / `getPodcastsDir` / `getRingtonesDir` / `getAppDataDir` /
`getAppExternalRootDir` / `getNativeLibDir` / `getLibraryDir` / `getIcloudDocumentsDir` /
`getAppInstallPath`（各方法对应路径见上面「0) 常用目录」表格）。

`FileEntry`：`{ path, name, isDirectory: false, size, lastModified, mimeType }`
`DirEntry`：`{ path, name, isDirectory: true, size: 0, lastModified, mimeType: 'inode/directory' }`

**错误约定**：错误信息带明确前缀，便于程序判断：
`NOT_FOUND` / `IS_A_DIRECTORY` / `NOT_A_DIRECTORY` / `PATH_EXISTS_AS_FILE` / `TARGET_EXISTS` /
`FILE_ALREADY_EXISTS` / `PATH_MUST_BE_ABSOLUTE` / `INVALID_NAME` / `INVALID_RELATIVE_PATH` /
`PERMISSION_DENIED`（附申请权限提示）。

## 平台差异与注意事项

| 事项 | Android | iOS |
| --- | --- | --- |
| 公共目录 | ✅（需 `MANAGE_EXTERNAL_STORAGE`，API 30+） | 🚫 不存在，`downloads/pictures/...` 为 null |
| 选择器 | SAF（`ACTION_OPEN_DOCUMENT` / `ACTION_OPEN_DOCUMENT_TREE`） | `UIDocumentPickerViewController` |
| 文件夹多选 | ❌ 系统不支持 | 系统支持，但本插件统一单选（跨平台一致） |
| 路径授权 | 权限（所有文件访问） | security-scoped bookmark（**已持久化到 UserDefaults，重启后仍可访问**） |
| 云盘来源 | 无法映射成路径 → `unmapped` | 选择后即可用（bookmark 保存在本机） |

**注意**：

1. **删除不可恢复**（直接作用到真实文件），请二次确认；
2. iOS 对外部目录的访问依赖 bookmark：**同一条路径前缀下**（含子目录）都能访问；
   如果用户移动了该文件夹，bookmark 可能失效，需要重新选择；
3. Android 11+ 的 `MANAGE_EXTERNAL_STORAGE` 在 Google Play 有政策限制（上架需说明用途），
   如果你的应用只读写自己的目录，用 `getAppExternalFilesDir()` 即可，**不需要**该权限；
4. `listFolder` 默认跳过隐藏文件（iOS `.skipsHiddenFiles`）；`maxEntries` 默认 5000。

### 为什么选择器选不了存储根目录 / Download？（Android 11+ 系统限制）

**这不是插件的问题**，是 Android 11（API 30）起 `ACTION_OPEN_DOCUMENT_TREE` 的规则：

| 位置 | 能否被选择器授权 |
| --- | --- |
| 内部存储根目录 `/storage/emulated/0` | ❌ 不能 |
| `Download` 目录本身 | ❌ 不能 |
| `Android/data`、`Android/obb` | ❌ 不能（且**有所有文件访问权限也访问不了**） |
| 其它普通目录（如 `Documents`、`Documents/Notes`） | ✅ 可以 |
| **用户新建的文件夹** | ✅ 可以（不在禁止名单里） |

所以你的体验是正常的：选不了根目录/Download，但新建一个文件夹就能选。

**推荐做法**：这类"按目录结构自由浏览"的应用（Markdown 阅读器、笔记树）**根本不需要系统选择器**：

```ts
// 直接拿根目录，然后靠 listFolder 一级级往下走
const root = (await NativeFiles.getSharedStorageDir()).path;   // /storage/emulated/0
const { entries } = await NativeFiles.listFolder({ path: root });
// → Alarms/ Android/ Audiobooks/ DCIM/ Documents/ Download/ ...

// 下载目录也可以直接列（不受 SAF 限制）
const download = (await NativeFiles.getDownloadsDir()).path;
await NativeFiles.listFolder({ path: download! });
```

前提是 Android 11+ 已授予「所有文件访问权限」（`requestAllFilesAccess()`）。

### 申请权限时"没弹窗"是正常的

`requestAllFilesAccess()` 在**已经授权**的情况下会**直接 resolve `{ granted: true }`，不弹任何界面**（这是设计如此）。
想验证真实流程，可以先在系统设置里关掉权限，或者用 adb：

```bash
# 撤销（注意：会杀掉 App 进程，这是系统的行为）
adb shell appops set com.myapp.testionic MANAGE_EXTERNAL_STORAGE default
```

撤销后再调用 `requestAllFilesAccess()`，会打开系统设置页
（`Settings → All files access → 你的 App → Allow access to manage all files`），
返回后 Promise 兑现为最新的 `{ granted }` 状态。

## 实测记录（2026-11-07 / 2026-11-08）

| 环境 | 结果 |
| --- | --- |
| 模拟器 Pixel 6 / Android 16（已授予所有文件访问） | **23 个目录 API 全部返回正确路径**；公共目录 `/storage/emulated/0/Documents` 建目录/写/读/列/改名/递归删全部通过 ✓ |
| 真机 Redmi K30S / Android 10（WRITE_EXTERNAL_STORAGE 已授权） | 同上全部通过（`checkAllFilesAccess: { granted: true, permission: 'WRITE_EXTERNAL_STORAGE' }`）✓ |
| 单条路径 API 耗时（真机） | 每个约 **2.3–28ms**（只查一条，不查其它）；模拟器 3.8–35ms |
| autotest（App 私有目录全链路） | `getAppExternalFilesDir` → 写 37B → 读回一致 → info 37B → 列目录 → 改名 → 删除后 `exists:false` ✓（`baseSource` 记录实际用了哪个目录 API） |
| 选择器 | 系统 UI，需手动操作（已验证：文件选择器正常弹出，返回键 → `{files:[],cancelled:true}`） |
| 权限申请 | 撤销权限后 `requestAllFilesAccess()` 打开系统设置页，返回后状态正确 ✓ |

## 性能实测（base64 桥 vs 本地 HTTP）

### 结论先行

- **10 几 MB 以内的文件走桥完全够用**：实测约 **10–11 MB/s**，8MB 文件写 0.78s、读 0.73s；
  常见文档 / 图片 / 中短音视频都在这个量级。
- 单次上限 **16 MB**（`readFile` 的 `length` 上限）；超过请分块或用 data-channel。
- **别忘了 JS 侧的开销**：`btoa`/`atob` 也是成本（8MB 编码 471ms、解码 138ms），
  端到端写 8MB ≈ 0.78s + 0.47s ≈ **1.25s（约 6.7 MB/s）**。大文本建议边处理边分块。
- **大文件（>20MB）或高频批量传输** → 用 [`capacitor-data-channel`](../capacitor-data-channel/) 的 HTTP/WS
  （实测 **93 MB/s 上传、322 MB/s 下载**，约 9–30 倍）。
- **只是要在 WebView 里展示**（图片/音频/PDF）→ 用 `Capacitor.convertFileSrc()`（见第 7 节），
  完全不过桥、由 WebView 流式解码。

### 实测数据（真机 Redmi K30S / Android 10，Capacitor 8.6.0）

| 文件大小 | 写入耗时 | 读取耗时 | 写吞吐 | 读吞吐 | JS `btoa` | JS `atob` |
| --- | --- | --- | --- | --- | --- | --- |
| 1 KB | 9.1 ms | 5.8 ms | — | — | 0.3 ms | 0.1 ms |
| 64 KB | 19.0 ms | 20.7 ms | 3.4 MB/s | 3.2 MB/s | 6.4 ms | 1.2 ms |
| 1 MB | 116.2 ms | 100.8 ms | 9.0 MB/s | 10.4 MB/s | 62.6 ms | 18.0 ms |
| 4 MB | 400.8 ms | 363.2 ms | 10.5 MB/s | 11.5 MB/s | 245.8 ms | 69.6 ms |
| 8 MB | 783.5 ms | 727.8 ms | **10.7 MB/s** | **11.5 MB/s** | 471.1 ms | 137.8 ms |

> 小文件（<10KB）是**每次调用的固定开销**占主导（约 5–10ms/次），所以"批量小文件"要合并成一次调用，
> 而不是循环调 100 次。

**分块读**（4MB 文件、每块 512KB、8 次 `readFile({offset,length})`）：**7.6 MB/s**，
比整文件略慢（每块一次桥往返 + JSON 解析），但内存占用恒定——超大文件推荐这种方式。

### 与 data-channel（本地 HTTP）的对比

| 场景 | 桥（本插件 base64） | data-channel HTTP |
| --- | --- | --- |
| 上传 | ~10 MB/s | **~93 MB/s** |
| 下载 | ~11 MB/s | **~322 MB/s** |
| 单次上限 | 16MB | 无（流式） |
| 进度 / 断点续传 | 自己做 | 内置 |
| 鉴权 | 无 | 随机端口 + OTP |

**选择建议**：≤16MB 一次性的读/写用本插件（简单、免端口）；更大或频繁批量传 → data-channel。

### 与官方 `@capacitor/filesystem` 的对比

**实现方式**（读的 8.1.5 源码）：

| 方面 | 说明 |
| --- | --- |
| 路径 | **没有绝对路径**：`Directory` 枚举（`Documents`/`Data`/`Library`/`Cache`/`External`/`ExternalStorage`/…）+ 相对 path |
| 读 | `readFile` 返回 base64（或传 `Encoding.UTF8` 返回字符串），同样走桥 |
| 写 | `writeFile({ data: string \| Blob })`，二进制需 base64；Android 写完外部存储会触发 `MediaScannerConnection.scanFile` |
| 大文件 | 8.1.5 新增 `readFileInChunks({ path, chunkSize }, callback)`：**原生主动推块**（`RETURN_CALLBACK`） |
| 显示 | `getUri()` 返回 Capacitor 的 `_capacitor_file_` URL，可直接给 `<img>`/`<video>` |

**性能对比**（同一台真机、同一份 base64 载荷）：

| 大小 | 本插件 写 / 读 | filesystem 写 / 读 |
| --- | --- | --- |
| 1 MB | 116.2 / 100.8 ms（9.0 / 10.4 MB/s） | 168.3 / 114.0 ms（6.2 / 9.2 MB/s） |
| 4 MB | 400.8 / 363.2 ms（10.5 / 11.5 MB/s） | 397.8 / 418.6 ms（10.5 / 10.0 MB/s） |
| 8 MB | 783.5 / 727.8 ms（10.7 / 11.5 MB/s） | 796.7 / 800.1 ms（10.5 / 10.5 MB/s） |
| 分块读 4MB（512KB/块） | 7.6 MB/s | **13.4 MB/s** |

结论：**整文件读写两者基本持平**（都是 base64 过同一个桥），本插件略快；官方的**分块/流式**更好
（原生推流，不用每块一次 JS→native 调用）。官方的短板在能力面：没有绝对路径、不能按目录树自由遍历、
没有公共目录权限管理——这些正是本插件的重点。

### 复现方式

基准脚本在仓库的 `.perf/`（未纳入 git）：

```bash
# 通过 CDP 在真机上执行
node scripts/webview-eval.mjs --port 9311 --file .perf/nf-base64-bench.js
node scripts/webview-eval.mjs --port 9311 --file .perf/nf-vs-filesystem-bench.js   # 需临时装 @capacitor/filesystem
```

> 上面的数字是 **0.5.1 之后**测的。0.5.1 修了两个影响读性能/正确性的问题：
> ① `readFile` 的 `offset`/`length` 因 `PluginCall.getLong()` 只认 `Long`（JS 小整数是 `Integer`）
> 而**一直没生效**；② 分块读用 `FileInputStream.skip()` 是 O(offset)，改成了 `RandomAccessFile.seek()`。

## 开发

```bash
cd plugins/capacitor-native-files
npm install
npm run build   # tsc → dist/esm
```

- Android：`android/src/main/java/com/testionic/nativefiles/NativeFilesPlugin.java`
  （`java.io.File` 路径操作 + SAF 选择器 + URI→路径解析 + 所有文件访问权限）
- iOS：`ios/Sources/NativeFilesPlugin/NativeFilesPlugin.swift`
  （`FileManager` + `UIDocumentPicker` + security-scoped bookmark 持久化 + 最长祖先路径授权）

## API 字段详解（每个入参 / 返回值）

> 本节是 `src/definitions.ts` 里 JSDoc 的完整镜像：所有字段的含义、单位、取值范围和边界行为都在这里。
> 想直接看类型注释也可以打开 `src/definitions.ts`（IDE 里 hover 参数名即可看到）。

### `pickFiles(options?) → PickFilesResult`

**入参**

| 字段 | 类型 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `multiple` | `boolean` | 否 | `false` | 是否多选。Android 用 `EXTRA_ALLOW_MULTIPLE`；iOS 用 `allowsMultipleSelection` |
| `mimeTypes` | `string[]` | 否 | 不过滤 | MIME 过滤，如 `['image/*','text/markdown']`。**Android 真正生效**（`EXTRA_MIME_TYPES`）；**iOS 目前忽略**（用 `UTType.item`） |

**返回值**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `files` | `FileEntry[]` | 选中的文件；取消时为空数组 |
| `cancelled` | `boolean` | 是否被用户取消 |
| `unmapped` | `string[]?` | **只在有值时返回**：无法映射成真实路径的 URI（Android 云盘/第三方 SAF provider） |
| `warning` | `string?` | 有 `unmapped` 时附带的英文提示 |

**`FileEntry`（文件条目，`listFolder` 也用）**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `path` | `string` | **真实绝对路径**（可直接拼子路径、喂给本插件其它 API 或 `convertFileSrc`） |
| `name` | `string` | 文件名（含扩展名，不含目录） |
| `isDirectory` | `false` | 恒为 `false`，用于和 `DirEntry` 做联合类型收窄 |
| `size` | `number` | 字节数（目录恒 0） |
| `lastModified` | `number` | 最后修改时间，**Unix 毫秒**（未知为 0） |
| `mimeType` | `string` | 按扩展名推断的 MIME；未知为 `''` |

### `pickFolders(options?) → PickFoldersResult`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `options.multiple` | `boolean?` | 保留字段；**两端目前都是单选**（Android SAF 不支持文件夹多选，iOS 为一致也单选） |
| `folders` | `DirEntry[]` | 选中的目录（最多 1 个；取消为空数组） |
| `cancelled` | `boolean` | 是否取消 |

**`DirEntry`**：`{ path, name, isDirectory: true, size: 0, lastModified, mimeType: 'inode/directory' }`

### `readFile(options) → ReadFileResult`

| 入参 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `path` | `string` | 必填 | 文件绝对路径 |
| `offset` | `number?` | `0` | 起始字节偏移；超过文件大小时返回空内容 |
| `length` | `number?` | `16MB` | 读取字节数；**上限 16MB**，超出按 16MB 截断 |

| 返回 | 类型 | 说明 |
| --- | --- | --- |
| `path` | `string` | 原样返回文件路径 |
| `base64` | `string` | 本次读取内容的 base64（标准、无换行） |
| `bytes` | `number` | **本次实际读取**的字节数（= base64 解码长度） |
| `size` | `number` | **文件总大小**（不是本次读取长度） |
| `offset` | `number` | 原样返回起始偏移，便于分块拼接 |

### `writeFile(options) → WriteFileResult`

| 入参 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `path` | `string` | 必填 | 目标绝对路径（**父目录不存在会自动递归创建**） |
| `base64` | `string` | 必填 | 要写入的内容（base64） |
| `mode` | `'create' \| 'overwrite' \| 'append'` | `overwrite` | `create` 已存在则 reject（`FILE_ALREADY_EXISTS`）；`append` 追加末尾 |

| 返回 | 类型 | 说明 |
| --- | --- | --- |
| `path` | `string` | 写入的绝对路径 |
| `bytes` | `number` | 本次写入字节数 |
| `size` | `number` | 写入后的**文件总大小**（`append` 时大于 `bytes`） |

### `deleteFile({ path }) → DeleteResult`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `deleted` | `boolean` | 是否真的删除了（文件不存在时 `false`，**不报错**） |
| `reason` | `string?` | `NOT_FOUND` 等 |

### `listFolder(options) → ListFolderResult`

| 入参 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `path` | `string` | 必填 | 目录绝对路径 |
| `recursive` | `boolean?` | `false` | 递归展开子目录（深度优先） |
| `maxEntries` | `number?` | `5000` | 最多返回条数（上限 20000） |

| 返回 | 类型 | 说明 |
| --- | --- | --- |
| `path` | `string` | 被列的目录路径 |
| `entries` | `FsEntry[]` | 条目（**文件夹在前**，再按名称大小写不敏感升序；iOS 跳过隐藏文件，Android 不跳过） |
| `truncated` | `boolean` | 是否因 `maxEntries` 被截断 |

### `createFolder({ path }) → { path, created }`

`path` 是**完整目标路径**（父目录自动创建）。已存在且是目录 → `created: false`（不报错）；
已存在但是文件 → reject `PATH_EXISTS_AS_FILE`。

### `deleteFolder({ path, recursive? }) → DeleteResult`

默认**只能删空目录**：非空返回 `{ deleted: false, reason: 'NOT_EMPTY_OR_FAILED', hint: '...' }`；
`recursive: true` 递归删除（**不可恢复**）。

### `renameFolder({ path, newName }) → { renamed, path, from }`

同一个父目录内改名（**文件和文件夹都能用**）。`newName` 只能是简单名称（不含 `/`，不能是 `.`/`..`）；
目标已存在 → reject `TARGET_EXISTS`。返回的 `path` 是**新路径**，`from` 是原路径。

### `getInfo({ path }) → PathInfo`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `path` | `string` | 绝对路径 |
| `name` | `string` | 文件名/目录名 |
| `exists` | `boolean` | 是否存在（**不存在时不 reject**，其余字段为零值） |
| `isDirectory` | `boolean` | 是否目录 |
| `isFile` | `boolean` | 是否普通文件（存在且非目录） |
| `size` | `number` | 字节数（目录 0） |
| `lastModified` | `number` | Unix 毫秒（未知 0） |
| `readable` | `boolean` | 当前进程是否可读 |
| `writable` | `boolean` | 当前进程是否可写 |
| `mimeType` | `string` | 文件按扩展名推断；目录为 `'inode/directory'`；未知 `''` |

### `checkAllFilesAccess()` / `requestAllFilesAccess()` → AllFilesAccessStatus

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `granted` | `boolean` | 是否已获得"所有文件访问权限"（Android）；iOS 恒 `true` |
| `applicable` | `boolean?` | iOS 为 `false`（没有该权限概念） |
| `message` | `string?` | iOS 上的说明文案 |
| `androidVersion` | `number?` | Android API level（如 `36`） |
| `requiresSettings` | `boolean?` | Android 11+ 为 `true`（需跳系统设置页）；≤10 为 `false`（运行时权限） |
| `permission` | `string?` | `MANAGE_EXTERNAL_STORAGE` 或 `WRITE_EXTERNAL_STORAGE` |

> `requestAllFilesAccess()` 在**已授权时直接返回 `{ granted: true }`，不弹任何界面**（设计如此）。
> 撤销权限（系统设置或 `adb shell appops set <pkg> MANAGE_EXTERNAL_STORAGE default`）会**杀掉 App 进程**。

### 23 个目录 API → DirectoryPathResult

统一返回 `{ path: string | null, reason?: string }`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `path` | `string \| null` | 绝对路径；`null` 表示该平台没有这个目录 |
| `reason` | `string?` | `path` 为 `null` 时的原因：`NOT_APPLICABLE_ON_IOS`（iOS 无公共目录）/ `NOT_APPLICABLE_ON_ANDROID`（Android 无该概念）/ `EXTERNAL_STORAGE_NOT_MOUNTED`（外置存储未挂载）/ `ICLOUD_NOT_AVAILABLE`（未开启 iCloud 能力） |

> **`path` 不为 `null` 不代表目录已存在**——它只表示"该目录应当在这里"。
> 判断存在性用 `getInfo({ path })`，创建用 `createFolder({ path })`。
> 每个方法对应的具体路径见前面「0) 常用目录：一个方法查一条路径」表格。