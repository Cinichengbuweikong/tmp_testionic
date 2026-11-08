<template>
  <div class="plugin-test">
    <h1>插件自检</h1>
    <p class="hint">当前平台：{{ platform }} · 原生可用：{{ isNative }}</p>

    <section class="card">
      <h2>NativeBasics</h2>
      <div class="actions">
        <button @click="runEcho">echo</button>
        <button @click="runToast">showToast</button>
        <button @click="loadDeviceInfo">getDeviceInfo</button>
      </div>
    </section>

    <section class="card">
      <h2>NativeStatusBar</h2>
      <div class="actions">
        <button v-for="item in colors" :key="item.value" @click="applyColor(item.value)">
          {{ item.label }}
        </button>
      </div>
      <div class="actions">
        <button @click="applyStyle('light')">亮色图标</button>
        <button @click="applyStyle('dark')">暗色图标</button>
        <button @click="applyStyle('default')">默认</button>
        <button @click="loadStatusBarInfo">getInfo</button>
      </div>
    </section>

    <section class="card">
      <h2>SetForceDark</h2>
      <div class="actions">
        <button @click="applyTheme('system')">跟随系统</button>
        <button @click="applyTheme('light')">强制亮色</button>
        <button @click="applyTheme('dark')">强制暗色</button>
        <button @click="loadDarkInfo">主题信息</button>
      </div>
      <div class="actions">
        <button @click="toggleForceDark(true)">forceDark ON</button>
        <button @click="toggleForceDark(false)">forceDark OFF</button>
      </div>
    </section>

    <section class="card">
      <h2>SystemInfo</h2>
      <div class="actions">
        <button @click="loadSystemInfo">读取系统信息</button>
      </div>
      <p class="hint">
        屏幕/视口/安全区/系统栏/系统/WebView 等综合信息；viewport.css 就是 CSS 里 100% 的大小。
      </p>
    </section>

    <section class="card">
      <h2>Rotation</h2>
      <div class="actions">
        <button @click="loadRotation">读取旋转状态</button>
        <button @click="startRotationListen">开始监听</button>
        <button @click="stopRotationListen">停止监听</button>
      </div>
      <div class="actions">
        <button @click="lockLandscape">锁定横屏</button>
        <button @click="lockPortrait">锁定竖屏</button>
        <button @click="unlockRotation">解锁（自动旋转）</button>
        <button @click="loadRotationLock">读取锁定状态</button>
      </div>
      <p class="hint">
        开始监听后旋转设备（模拟器可用 adb emu rotate），output 会打印 rotationChanged 事件；
        停止监听即解除。「锁定横屏」可用于视频全屏播放场景。
      </p>
    </section>

    <section class="card">
      <h2>Fullscreen</h2>
      <div class="actions">
        <button @click="fullscreenEnter">进入全屏</button>
        <button @click="fullscreenExit">退出全屏</button>
        <button @click="fullscreenToggle">切换全屏</button>
        <button @click="loadFullscreenState">读取全屏状态</button>
      </div>
      <div class="actions">
        <button @click="startFullscreenListen">监听全屏变化</button>
        <button @click="stopFullscreenListen">取消全屏监听</button>
      </div>
      <p class="hint">
        进入全屏会隐藏状态栏和导航栏，内容完整铺满屏幕；退出后恢复。
      </p>
    </section>

    <section class="card">
      <h2>KeyboardAvoiding</h2>
      <div class="actions">
        <button @click="enableKeyboardAvoid">开启避让</button>
        <button @click="disableKeyboardAvoid">关闭避让</button>
        <button @click="loadKeyboardState">读取键盘状态</button>
      </div>
      <div class="actions">
        <button @click="startKeyboardListen">监听键盘</button>
        <button @click="stopKeyboardListen">停止键盘监听</button>
      </div>
      <input
        class="kb-input"
        type="text"
        placeholder="点这里弹出软键盘测试"
      />
      <p class="hint">
        点输入框弹出键盘后，output 会打印 keyboardChanged 事件（含键盘高度）；开启避让时
        WebView 会自动缩到键盘上方。
      </p>
    </section>

    <section class="card">
      <h2>NativeFiles</h2>
      <div class="actions">
        <button @click="pickFiles">选择文件</button>
        <button @click="pickFolders">选择文件夹</button>
        <button @click="probeNativeFiles">文件 API 自检</button>
      </div>
      <div class="actions">
        <button @click="loadAppDirectories">应用目录</button>
        <button @click="checkAllFilesAccess">检查全文件权限</button>
        <button @click="requestAllFilesAccess">申请全文件权限</button>
      </div>
      <p class="hint">
        全部 API 使用<strong>真实绝对路径</strong>（可直接拼路径）；选择器需要手动操作。
        「应用目录」会逐个调用 23 个单条路径 API（某个取不到不影响其它）。
        读写公共目录（如 /storage/emulated/0/Documents）在 Android 11+ 需要「所有文件访问权限」，
        App 私有目录（getAppExternalFilesDir 等）任何版本都免权限。
      </p>
    </section>

    <section class="card">
      <h2>CppBridge（C/C++ 模板）</h2>
      <div class="actions">
        <button @click="cppCoreInfo">核心信息</button>
        <button @click="cppAdd">add（C++）</button>
        <button @click="cppCAdd">cAdd（C）</button>
        <button @click="cppCStrlen">strlen（C）</button>
      </div>
      <div class="actions">
        <button @click="cppBufferSelfTest">内存块自检（4MB）</button>
        <button @click="cppBufferBigTest">64MB 计时</button>
        <button @click="cppBufferStats">缓冲统计</button>
      </div>
      <p class="hint">
        <code>add</code> 由 C++ 编译单元实现，<code>cAdd</code>/<code>strlen</code> 由<b>纯 C</b> 编译单元实现；
        内存块 API 用 native 指针句柄在 C/C++ 与 native 之间零拷贝操作，<b>数据不过 JS 桥</b>。
        ⚠️ 句柄是 danger API：必须 free，进程重启失效，不要并发写同一 handle。
      </p>
    </section>

    <section class="card">
      <h2>文件 URL（Capacitor 内置 convertFileSrc）</h2>
      <div class="actions">
        <button @click="urlSchemeFetch">fetch 读取</button>
        <button @click="urlSchemeRange">fetch Range</button>
        <button @click="urlSchemeImage">图片加载</button>
        <button @click="urlSchemeScript">动态 script</button>
      </div>
      <div class="actions">
        <button @click="urlSchemeAll">一键自检</button>
        <button @click="urlSchemeCleanup">清理测试文件</button>
      </div>
      <img v-if="urlPreview" :src="urlPreview" class="url-preview" alt="scheme preview" />
      <p class="hint">
        Capacitor 内置把<strong>文件路径</strong>转成 WebView 可访问的 URL：
        Android 是 <code>https://localhost/_capacitor_file_/&lt;绝对路径&gt;</code>，
        iOS 是 <code>capacitor://localhost/_capacitor_file_/&lt;绝对路径&gt;</code>。
        不需要任何第三方插件；底层已支持 Range（视频拖动进度条）。
      </p>
    </section>

    <section class="card">
      <h2>ForegroundState</h2>
      <div class="actions">
        <button @click="loadForegroundState">读取应用状态</button>
        <button @click="startForegroundStateListen">监听状态变化</button>
        <button @click="stopForegroundStateListen">停止状态监听</button>
      </div>
      <p class="hint">
        切到后台再回到前台，output 会打印 stateChanged 事件（含 backgroundDuration 后台时长）。
      </p>
    </section>

    <section class="card">
      <h2>DataChannel</h2>
      <div class="actions">
        <button @click="startHttpServer">启动 HTTP</button>
        <button @click="stopHttpServer">停止 HTTP</button>
        <button @click="startWsServer">启动 WS</button>
        <button @click="stopWsServer">停止 WS</button>
      </div>
      <div class="actions">
        <button @click="dataChannelUpload">上传 10MB</button>
        <button @click="dataChannelUploadChunked">分块上传 100MB</button>
        <button @click="dataChannelDownload">下载测试文件</button>
        <button @click="dataChannelWebSocket">WS 回显 1MB</button>
      </div>
      <div class="actions">
        <input ref="dcFileInput" class="kb-input" type="file" style="flex: 1" />
        <button @click="dataChannelUploadFile">上传所选文件</button>
        <button @click="loadDataChannelInfo">读取信息</button>
        <button @click="startWithCustomRoot">自定义目录启动</button>
        <button @click="revokeAllDataOtps">撤销所有 OTP</button>
      </div>
      <p class="hint">
        HTTP / WS 独立启停；OTP 通过桥下发（抓包看不到），一次性 OTP 用完即废；
        媒体 reusable OTP 用完必须 revoke。
      </p>
    </section>

    <section class="card">
      <h2>WebViewToEdge</h2>
      <div class="actions">
        <button @click="setToEdge(true)">铺满整屏</button>
        <button @click="setToEdge(false)">恢复默认</button>
        <button @click="loadToEdgeInfo">安全区信息</button>
      </div>
      <p class="hint">
        开启后页面会铺到状态栏下面；给这个卡片加个
        <code>padding-top: var(--safe-area-inset-top)</code> 就能看到效果。
      </p>
    </section>

    <section class="card">
      <h2>配色样例（用来看 forceDark 是否把颜色搞坏）</h2>
      <div class="swatches">
        <div class="swatch" style="background: #f44336">#F44336</div>
        <div class="swatch" style="background: #4caf50">#4CAF50</div>
        <div class="swatch" style="background: #ffeb3b; color: #333">#FFEB3B</div>
        <div class="swatch" style="background: #2196f3">#2196F3</div>
        <div class="swatch" style="background: #ffffff; color: #333">#FFFFFF</div>
        <div class="swatch" style="background: #212121">#212121</div>
      </div>
    </section>

    <pre class="output">{{ output }}</pre>
  </div>
</template>

<script>
import { Capacitor } from '@capacitor/core';
import { ForegroundState } from 'capacitor-foreground-state';
import {
  DataChannel,
  encodeWsMessage,
  encodeWsMessageWithOtp,
  decodeWsMessage,
  decodeWsMessagesBatch,
  createWsOtpPool,
  WsOtpPool,
} from 'capacitor-data-channel';
import { NativeFiles } from 'capacitor-native-files';
import { CppBridge } from 'capacitor-cpp-bridge';
import { Fullscreen } from 'capacitor-fullscreen';
import { KeyboardAvoiding } from 'capacitor-keyboard-avoiding';
import { NativeBasics } from 'capacitor-native-basics';
import { NativeStatusBar } from 'capacitor-native-statusbar';
import { Rotation } from 'capacitor-native-rotation';
import { SetForceDark } from 'capacitor-set-force-dark';
import { SystemInfo } from 'capacitor-system-info';
import { WebViewToEdge } from 'capacitor-webview-to-edge';

export default {
  data() {
    return {
      platform: Capacitor.getPlatform(),
      isNative: Capacitor.isNativePlatform(),
      output: '',
      rotationHandle: null,
      fullscreenHandle: null,
      keyboardHandle: null,
      dataChannelInfo: null,
      foregroundStateHandle: null,
      urlPreview: '',
      colors: [
        { label: '橙 #FF5722', value: '#FF5722' },
        { label: '绿 #4CAF50', value: '#4CAF50' },
        { label: '蓝 #2196F3', value: '#2196F3' },
        { label: '白 #FFFFFF', value: '#FFFFFF' },
        { label: '黑 #000000', value: '#000000' },
        { label: '透明', value: 'transparent' },
      ],
    };
  },

  async mounted() {
    await this.loadDeviceInfo();

    if (this.$route.query.autotest) {
      await this.runAutotest();
    }
  },

  methods: {
    print(label, data) {
      this.output = `${label}\n${JSON.stringify(data, null, 2)}`;
    },

    error(label, err) {
      this.print(`${label} 失败`, { message: String((err && err.message) || err) });
    },

    /**
     * 自动化验证：CI（iOS 模拟器 / Android）里跑，把结果同时写到页面和 console。
     * console 那一行会被 Capacitor 转发到原生日志，CI 直接 grep。
     */
    async runAutotest() {
      const results = { platform: this.platform };
      try {
        results.echo = await NativeBasics.echo({ value: 'autotest' });
        await NativeBasics.showToast({ text: 'autotest toast', duration: 'short' });
        results.toast = 'ok';
        results.deviceInfo = await NativeBasics.getDeviceInfo();

        results.statusBar = {
          set: await NativeStatusBar.setBackgroundColor({ color: '#FF5722' }),
          info: null,
        };
        await NativeStatusBar.setStyle({ style: 'dark' });
        results.statusBar.info = await NativeStatusBar.getInfo();

        const info = results.statusBar.info;
        results.ok = info.color === '#FFFF5722' && info.style === 'dark' && info.height > 0;
        if (!results.ok) {
          results.reason = `unexpected status bar info: ${JSON.stringify(info)}`;
        }

        // SetForceDark：注入 data-theme + 关掉 forceDark
        await SetForceDark.setTheme({ theme: 'dark' });
        await new Promise((resolve) => setTimeout(resolve, 300));
        results.setForceDark = {
          info: await SetForceDark.getInfo(),
          dataTheme: document.documentElement.getAttribute('data-theme'),
        };
        if (results.setForceDark.dataTheme !== 'dark') {
          results.ok = false;
          results.reason = `setForceDark injection failed: ${JSON.stringify(results.setForceDark)}`;
        }

        // SystemInfo：13 个独立 API 的结构与数值自洽性检查（物理 px = css × density）
        try {
          const [screen, viewport, safeArea, statusBar, navigationBar, cutout, system, webView, memory, storage, battery, network, app] = await Promise.all([
            SystemInfo.getScreenInfo(),
            SystemInfo.getViewport(),
            SystemInfo.getSafeAreaInsets(),
            SystemInfo.getStatusBarInfo(),
            SystemInfo.getNavigationBarInfo(),
            SystemInfo.getCutoutInfo(),
            SystemInfo.getSystemInfo(),
            SystemInfo.getWebViewInfo(),
            SystemInfo.getMemoryInfo(),
            SystemInfo.getStorageInfo(),
            SystemInfo.getBatteryInfo(),
            SystemInfo.getNetworkInfo(),
            SystemInfo.getAppInfo(),
          ]);
          results.systemInfo = {
            screen, viewport, safeArea, statusBar, navigationBar, cutout,
            system, webView, memory, storage, battery, network, app,
          };
          const checks = [
            ['screen', !!screen && screen.px.width > 0 && screen.density > 0 && Math.abs(screen.css.width * screen.density - screen.px.width) < 1],
            ['viewport', !!viewport && viewport.css.width > 0 && viewport.css.width <= screen.css.width + 1],
            ['safeArea', !!safeArea && typeof safeArea.css.top === 'number' && typeof safeArea.px.top === 'number'],
            ['statusBar', !!statusBar && typeof statusBar.px.height === 'number' && typeof statusBar.css.height === 'number'],
            ['navigationBar', !!navigationBar && typeof navigationBar.px.height === 'number' && typeof navigationBar.css.height === 'number'],
            ['cutout', typeof cutout.exists === 'boolean'],
            ['system', !!system && system.platform === this.platform && system.osVersion.length > 0],
            ['webView', !!webView && typeof webView.userAgent === 'string'],
            ['memory', typeof memory.totalBytes === 'number'],
            ['storage', typeof storage.totalBytes === 'number'],
            ['battery', typeof battery.level === 'number'],
            ['network', ['wifi', 'cellular', 'ethernet', 'vpn', 'none', 'unknown'].includes(network.type)],
            ['app', typeof app.version === 'string'],
          ];
          const failed = checks.filter(([, ok]) => !ok).map(([name]) => name);
          if (failed.length) {
            results.ok = false;
            // 数据本体已在 results.systemInfo 里，reason 只放失败项，避免日志超长被截断
            results.reason = `system-info check failed: ${failed.join(', ')}`;
          }
        } catch (err) {
          results.ok = false;
          results.systemInfoError = String((err && err.message) || err);
        }

        // Rotation：读取状态 + 监听/解除监听冒烟
        try {
          const rot = await Rotation.getRotation();
          const handle = await Rotation.addListener('rotationChanged', () => {});
          const hasHandle = !!handle && typeof handle.remove === 'function';
          await handle.remove();
          results.rotation = { state: rot, hasHandle, removed: true };
          const rotOk = !!rot
            && ['portrait', 'landscape', 'unknown'].includes(rot.orientation)
            && [0, 90, 180, 270].includes(rot.rotationDegrees)
            && typeof rot.isPortrait === 'boolean'
            && typeof rot.isLandscape === 'boolean'
            && hasHandle;
          if (!rotOk) {
            results.ok = false;
            results.reason = `rotation check failed: ${JSON.stringify(rot)}`;
          }

          // 方向锁定：锁横屏 → 读状态/当前方向 → 解锁（无论成败都要解锁）
          const lockOn = await Rotation.lock({ orientation: 'landscape' });
          await new Promise((resolve) => setTimeout(resolve, 700));
          const lockState = await Rotation.getLock();
          const rotAfterLock = await Rotation.getRotation();
          const unlockState = await Rotation.unlock();
          await new Promise((resolve) => setTimeout(resolve, 500));
          results.rotationLock = { lockOn, lockState, rotAfterLock, unlockState };
          const lockOk = !!lockOn && lockOn.locked === true && lockOn.orientation === 'landscape'
            && !!lockState && lockState.locked === true && lockState.orientation === 'landscape'
            && !!unlockState && unlockState.locked === false && unlockState.orientation === 'auto';
          if (!lockOk) {
            results.ok = false;
            results.reason = `rotation lock check failed: ${JSON.stringify(results.rotationLock)}`;
          }
        } catch (err) {
          results.ok = false;
          results.rotationError = String((err && err.message) || err);
          try {
            await Rotation.unlock();
          } catch (ignored) {
            // 尽力恢复自动旋转
          }
        }

        // KeyboardAvoiding：状态结构 + 开关 + 监听/解除（不依赖真实键盘）
        try {
          const kbInitial = await KeyboardAvoiding.getState();
          const kbOn = await KeyboardAvoiding.enable();
          const kbState = await KeyboardAvoiding.getState();
          const kbHandle = await KeyboardAvoiding.addListener('keyboardChanged', () => {});
          const kbHasHandle = !!kbHandle && typeof kbHandle.remove === 'function';
          await kbHandle.remove();
          const kbOff = await KeyboardAvoiding.disable();
          results.keyboardAvoiding = { initial: kbInitial, on: kbOn, state: kbState, off: kbOff, hasHandle: kbHasHandle };
          const kbOk = !!kbInitial
            && typeof kbInitial.visible === 'boolean'
            && typeof kbInitial.height === 'number'
            && typeof kbInitial.viewportHeight === 'number'
            && kbInitial.viewportHeight > 0
            && !!kbOn && kbOn.autoAvoid === true
            && !!kbState && kbState.autoAvoid === true
            && !!kbOff && kbOff.autoAvoid === false
            && kbHasHandle;
          if (!kbOk) {
            results.ok = false;
            results.reason = `keyboard-avoiding check failed: ${JSON.stringify(results.keyboardAvoiding)}`;
          }
        } catch (err) {
          results.ok = false;
          results.keyboardAvoidingError = String((err && err.message) || err);
        }

        // DataChannel：HTTP / WebSocket 独立 + OTP 全链路 + 安全断言
        try {
          // 所有请求带超时，避免某个请求挂起导致整个自检卡死（CI 会因此看不到结果）
          const fetchT = async (url, options = {}, timeoutMs = 8000) => {
            const controller = new AbortController();
            const timer = setTimeout(() => controller.abort(), timeoutMs);
            try {
              return await fetch(url, { ...options, signal: controller.signal });
            } finally {
              clearTimeout(timer);
            }
          };
          const http = await DataChannel.startHttp();
          const health = await (await fetchT(`${http.baseUrl}/health`)).json();

          // 1MB 上传（写：一次性 OTP）
          const payload = new Uint8Array(1024 * 1024);
          for (let i = 0; i < payload.length; i += 4096) {
            payload[i] = i & 0xff;
          }
          const writeOtp = await DataChannel.createHttpOtp({ scope: 'write', path: 'selftest/test.bin' });
          const uploadRes = await fetchT(`${http.baseUrl}/upload?path=selftest/test.bin`, {
            method: 'PUT',
            headers: { Authorization: `OTP ${writeOtp.otp}` },
            body: payload,
          });
          const upload = await uploadRes.json();
          // 重放同一个写 OTP → 404（一次性）
          const replayWriteRes = await fetchT(`${http.baseUrl}/upload?path=selftest/test.bin`, {
            method: 'PUT',
            headers: { Authorization: `OTP ${writeOtp.otp}` },
            body: new Uint8Array([1, 2, 3]),
          });

          // 下载并校验（读：一次性 OTP）
          const readOtp = await DataChannel.createHttpOtp({ scope: 'read', path: 'selftest/test.bin' });
          const downloadRes = await fetchT(`${http.baseUrl}/download?path=selftest/test.bin&otp=${readOtp.otp}`);
          const downloaded = await downloadRes.arrayBuffer();
          const downloadedBytes = new Uint8Array(downloaded);
          const same =
            downloadedBytes.byteLength === payload.byteLength &&
            downloadedBytes[0] === payload[0] &&
            downloadedBytes[4096] === payload[4096] &&
            downloadedBytes[downloadedBytes.length - 1] === payload[payload.length - 1];
          // 重放同一个读 OTP → 404
          const replayReadRes = await fetchT(`${http.baseUrl}/download?path=selftest/test.bin&otp=${readOtp.otp}`);

          // OTP 绑定路径：绑 A 请求 B → 404
          const pathOtp = await DataChannel.createHttpOtp({ scope: 'read', path: 'selftest/other.bin' });
          const wrongPathRes = await fetchT(`${http.baseUrl}/download?path=selftest/test.bin&otp=${pathOtp.otp}`);

          // scope：read OTP 不能写 → 404
          const readForWriteOtp = await DataChannel.createHttpOtp({ scope: 'read', path: 'selftest/test.bin' });
          const writeWithReadRes = await fetchT(`${http.baseUrl}/upload?path=selftest/test.bin`, {
            method: 'PUT',
            headers: { Authorization: `OTP ${readForWriteOtp.otp}` },
            body: new Uint8Array([9]),
          });

          // 写 + reusable → 必须被拒绝
          let reusableWriteRejected = false;
          try {
            await DataChannel.createHttpOtp({ scope: 'write', path: 'selftest/test.bin', reusable: true });
          } catch (err) {
            reusableWriteRejected = true;
          }

          // 创建"路径穿越"OTP → 必须被拒绝
          let traversalRejected = false;
          try {
            await DataChannel.createHttpOtp({ scope: 'read', path: '../escape.txt' });
          } catch (err) {
            traversalRejected = true;
          }

          // 媒体 reusable OTP：两次读都成功，revoke 后失败
          const mediaOtp = await DataChannel.createHttpOtp({ scope: 'read', path: 'selftest/test.bin', reusable: true });
          const mediaFirst = await fetchT(`${http.baseUrl}/download?path=selftest/test.bin&otp=${mediaOtp.otp}`);
          const mediaSecond = await fetchT(`${http.baseUrl}/download?path=selftest/test.bin&otp=${mediaOtp.otp}`);
          await DataChannel.revokeOtp({ otp: mediaOtp.otp });
          const mediaAfterRevoke = await fetchT(`${http.baseUrl}/download?path=selftest/test.bin&otp=${mediaOtp.otp}`);

          // 安全：无 OTP / 未知路由 / 已移除路由 / 预检
          const noOtpRes = await fetchT(`${http.baseUrl}/download?path=selftest/test.bin`);
          const unknownRes = await fetchT(`${http.baseUrl}/nope?otp=nope`);
          const removedInfoRes = await fetchT(`${http.baseUrl}/info?path=selftest/test.bin&otp=nope`);
          const removedListRes = await fetchT(`${http.baseUrl}/list?path=selftest&otp=nope`);
          const removedFileRes = await fetchT(`${http.baseUrl}/file?path=selftest&otp=nope`);
          const preflightRes = await fetchT(`${http.baseUrl}/upload?path=selftest/test.bin`, {
            method: 'OPTIONS',
            headers: { 'Access-Control-Request-Method': 'PUT' },
          });

          // ---- 断点续传协议：offset / status / finalize / sha256 ----
          // 路径带随机后缀：保证测试幂等（上一轮留下的最终文件不会影响本轮断言）
          const rsTag = Math.random().toString(36).slice(2, 8);
          const rsPath = `selftest/resumable-${rsTag}.bin`;
          const rsChunk = 100 * 1024;
          const rsTotal = rsChunk * 3;
          const rs1 = new Uint8Array(rsChunk).fill(7);
          const rs2 = new Uint8Array(rsChunk).fill(8);
          const rs3 = new Uint8Array(rsChunk).fill(9);
          const putChunk = async (offset, body) => {
            const otp = await DataChannel.createHttpOtp({ scope: 'write', path: rsPath });
            return fetchT(`${http.baseUrl}/upload?path=${rsPath}&offset=${offset}&total=${rsTotal}`, {
              method: 'PUT',
              headers: { Authorization: `OTP ${otp.otp}` },
              body,
            });
          };
          const rsC1 = await putChunk(0, rs1);
          const rsC2 = await putChunk(rsChunk, rs2);
          // offset 不匹配 → 409 + 实际 size
          const rsBadOffset = await putChunk(12345, rs3);
          const rsBadOffsetJson = await rsBadOffset.json();
          // status → partSize
          const rsStatusOtp = await DataChannel.createHttpOtp({ scope: 'write', path: rsPath });
          const rsStatus = await (
            await fetchT(`${http.baseUrl}/upload?path=${rsPath}&status=1&otp=${rsStatusOtp.otp}`)
          ).json();
          // finalize 总长不对 → 409，且最终文件不存在
          const rsFinBadOtp = await DataChannel.createHttpOtp({ scope: 'write', path: rsPath });
          const rsFinBad = await fetchT(`${http.baseUrl}/upload?path=${rsPath}&finalize=1&total=999999`, {
            method: 'POST',
            headers: { Authorization: `OTP ${rsFinBadOtp.otp}` },
          });
          const rsFinBadJson = await rsFinBad.json();
          const rsBeforeFinalOtp = await DataChannel.createHttpOtp({ scope: 'read', path: rsPath });
          const rsBeforeFinal = await fetchT(`${http.baseUrl}/download?path=${rsPath}&otp=${rsBeforeFinalOtp.otp}`);
          // 补齐第三块 → 正确 finalize（带 sha256）
          const rsC3 = await putChunk(rsChunk * 2, rs3);
          const rsFull = new Uint8Array(rsTotal);
          rsFull.set(rs1, 0);
          rsFull.set(rs2, rsChunk);
          rsFull.set(rs3, rsChunk * 2);
          const rsDigest = await crypto.subtle.digest('SHA-256', rsFull);
          const rsSha = Array.from(new Uint8Array(rsDigest))
            .map((b) => b.toString(16).padStart(2, '0'))
            .join('');
          const rsFinOtp = await DataChannel.createHttpOtp({ scope: 'write', path: rsPath });
          const rsFin = await fetchT(
            `${http.baseUrl}/upload?path=${rsPath}&finalize=1&total=${rsTotal}&sha256=${rsSha}`,
            { method: 'POST', headers: { Authorization: `OTP ${rsFinOtp.otp}` } },
          );
          const rsFinJson = await rsFin.json();
          // 内容校验：8 + 8 + 9 三段
          const rsVerifyOtp = await DataChannel.createHttpOtp({ scope: 'read', path: rsPath });
          const rsVerify = new Uint8Array(
            await (await fetchT(`${http.baseUrl}/download?path=${rsPath}&otp=${rsVerifyOtp.otp}`)).arrayBuffer(),
          );
          const rsContentOk =
            rsVerify.length === rsTotal &&
            rsVerify[0] === 7 &&
            rsVerify[rsChunk] === 8 &&
            rsVerify[rsChunk * 2] === 9;
          // sha256 错误 → 409
          const rsHashPath = `selftest/hash-${rsTag}.bin`;
          const rsHashOtp = await DataChannel.createHttpOtp({ scope: 'write', path: rsHashPath });
          await fetchT(`${http.baseUrl}/upload?path=${rsHashPath}&offset=0&total=4`, {
            method: 'PUT',
            headers: { Authorization: `OTP ${rsHashOtp.otp}` },
            body: new Uint8Array([1, 2, 3, 4]),
          });
          const rsHashFinOtp = await DataChannel.createHttpOtp({ scope: 'write', path: rsHashPath });
          const rsHash = await fetchT(
            `${http.baseUrl}/upload?path=${rsHashPath}&finalize=1&total=4&sha256=${'0'.repeat(64)}`,
            { method: 'POST', headers: { Authorization: `OTP ${rsHashFinOtp.otp}` } },
          );
          const rsHashJson = await rsHash.json();
          // append=1 已废弃 → 400
          const rsAppendOtp = await DataChannel.createHttpOtp({ scope: 'write', path: rsPath });
          const rsAppend = await fetchT(`${http.baseUrl}/upload?path=${rsPath}&append=1`, {
            method: 'PUT',
            headers: { Authorization: `OTP ${rsAppendOtp.otp}` },
            body: new Uint8Array([1]),
          });
          const rsAppendJson = await rsAppend.json();
          // 中断上传：目标文件不应出现（.part 可能残留部分数据，可用 status 续传）
          const rsAbortPath = `selftest/aborted-${rsTag}.bin`;
          const rsAbortBody = new Uint8Array(200 * 1024).fill(1);
          const rsAbortOtp = await DataChannel.createHttpOtp({ scope: 'write', path: rsAbortPath });
          const rsAbort = await new Promise((resolve) => {
            let settled = false;
            const done = (value) => {
              if (settled) {
                return;
              }
              settled = true;
              resolve(value);
            };
            const xhr = new XMLHttpRequest();
            xhr.open('PUT', `${http.baseUrl}/upload?path=${rsAbortPath}&offset=0&total=${rsAbortBody.length}`);
            xhr.setRequestHeader('Authorization', `OTP ${rsAbortOtp.otp}`);
            xhr.onload = () => done({ status: xhr.status, aborted: false });
            xhr.onerror = () => done({ status: 0, aborted: true });
            xhr.onabort = () => done({ status: 0, aborted: true });
            xhr.send(rsAbortBody);
            setTimeout(() => {
              try {
                xhr.abort();
              } catch (ignored) {
                // 忽略
              }
            }, 20);
            // 兜底：某些平台 abort 后可能不回调，避免整个 autotest 卡死
            setTimeout(() => done({ status: 0, aborted: true, timeout: true }), 3000);
          });
          await new Promise((resolve) => setTimeout(resolve, 400));
          const rsAbortStatusOtp = await DataChannel.createHttpOtp({ scope: 'write', path: rsAbortPath });
          const rsAbortStatus = await (
            await fetchT(`${http.baseUrl}/upload?path=${rsAbortPath}&status=1&otp=${rsAbortStatusOtp.otp}`)
          ).json();
          const rsAbortDownloadOtp = await DataChannel.createHttpOtp({ scope: 'read', path: rsAbortPath });
          const rsAbortDownload = await fetchT(
            `${http.baseUrl}/download?path=${rsAbortPath}&otp=${rsAbortDownloadOtp.otp}`,
          );

          // WebSocket：握手一次性 OTP + 每条消息一次性 OTP（协议 [otp][0x00][payload]）
          const wsInfo = await DataChannel.startWebSocket();
          const wsHandshakeOtp = await DataChannel.createWsOtp();
          const wsStartedAt = performance.now();
          const wsResult = await new Promise((resolve) => {
            let settled = false;
            const done = (value) => {
              if (!settled) {
                settled = true;
                resolve(value);
              }
            };
            const wsPayload = new Uint8Array(256 * 1024);
            wsPayload[0] = 0x11;
            wsPayload[wsPayload.length - 1] = 0x22;
            let ws;
            try {
              ws = new WebSocket(wsInfo.wsUrl, [`otp.${wsHandshakeOtp.otp}`]);
            } catch (err) {
              done({ error: 'construct: ' + ((err && err.message) || err) });
              return;
            }
            ws.binaryType = 'arraybuffer';
            const timer = setTimeout(() => {
              try {
                ws.close();
              } catch (ignored) {
                // 忽略
              }
              done({ error: 'timeout' });
            }, 8000);
            ws.onopen = async () => {
              try {
                // 每条消息：encodeWsMessage 内部 createWsOtp + 拼 [otp][0x00][payload]
                ws.send(await encodeWsMessage(wsPayload));
              } catch (err) {
                clearTimeout(timer);
                done({ error: 'encode: ' + ((err && err.message) || err) });
              }
            };
            ws.onmessage = async (event) => {
              clearTimeout(timer);
              try {
                // 每条消息：decodeWsMessage 内部 validateAndConsumeOTP
                const decoded = await decodeWsMessage(event.data);
                try {
                  ws.close();
                } catch (ignored) {
                  // 忽略
                }
                if (!decoded.ok) {
                  done({ error: 'decode: ' + decoded.error });
                  return;
                }
                const received = new Uint8Array(decoded.payload);
                done({
                  bytes: received.byteLength,
                  sameLength: received.byteLength === wsPayload.byteLength,
                  sameHead: received[0] === wsPayload[0],
                  sameTail: received[received.length - 1] === wsPayload[wsPayload.length - 1],
                  ms: Math.round(performance.now() - wsStartedAt),
                });
              } catch (err) {
                done({ error: 'decode: ' + ((err && err.message) || err) });
              }
            };
            ws.onerror = () => {
              clearTimeout(timer);
              try {
                ws.close();
              } catch (ignored) {
                // 忽略
              }
              done({ error: 'ws error' });
            };
          });

          // 重放同一个握手 OTP → 连接必须被拒绝
          const wsReplay = await new Promise((resolve) => {
            try {
              const socket = new WebSocket(wsInfo.wsUrl, [`otp.${wsHandshakeOtp.otp}`]);
              const timer = setTimeout(() => {
                try {
                  socket.close();
                } catch (ignored) {
                  // 忽略
                }
                resolve({ rejected: false, timeout: true });
              }, 5000);
              socket.onopen = () => {
                clearTimeout(timer);
                try {
                  socket.close();
                } catch (ignored) {
                  // 忽略
                }
                resolve({ rejected: false });
              };
              socket.onerror = () => {
                clearTimeout(timer);
                resolve({ rejected: true });
              };
            } catch (err) {
              resolve({ rejected: true });
            }
          });

          // 消息级：无 OTP 的消息 → Server 回错误消息（消息被丢弃）
          const wsNoOtp = await (async () => {
            const handshake = await DataChannel.createWsOtp();
            const ws = new WebSocket(wsInfo.wsUrl, [`otp.${handshake.otp}`]);
            ws.binaryType = 'arraybuffer';
            return await new Promise((resolve) => {
              const timer = setTimeout(() => {
                try {
                  ws.close();
                } catch (ignored) {
                  // 忽略
                }
                resolve({ ok: null, error: 'timeout' });
              }, 5000);
              ws.onopen = () => {
                ws.send(new Uint8Array([1, 2, 3])); // 不带 OTP
              };
              ws.onmessage = async (event) => {
                clearTimeout(timer);
                const decoded = await decodeWsMessage(event.data);
                try {
                  ws.close();
                } catch (ignored) {
                  // 忽略
                }
                resolve({ ok: decoded.ok, error: decoded.error });
              };
              ws.onerror = () => {
                clearTimeout(timer);
                resolve({ ok: null, error: 'ws error' });
              };
            });
          })();

          // 消息级：同一 OTP 重放 → 第二次必须被拒
          const wsMessageReplay = await (async () => {
            const handshake = await DataChannel.createWsOtp();
            const msgOtp = await DataChannel.createWsOtp();
            const payload = new Uint8Array([0xab, 0xcd]);
            const otpBytes = new TextEncoder().encode(msgOtp.otp);
            const frame = new Uint8Array(otpBytes.length + 1 + payload.length);
            frame.set(otpBytes, 0);
            frame[otpBytes.length] = 0;
            frame.set(payload, otpBytes.length + 1);

            const ws = new WebSocket(wsInfo.wsUrl, [`otp.${handshake.otp}`]);
            ws.binaryType = 'arraybuffer';
            const responses = [];
            return await new Promise((resolve) => {
              const timer = setTimeout(() => {
                try {
                  ws.close();
                } catch (ignored) {
                  // 忽略
                }
                resolve({ firstOk: null, secondOk: null, error: 'timeout' });
              }, 6000);
              ws.onopen = () => {
                ws.send(frame); // 第 1 次：合法
              };
              ws.onmessage = async (event) => {
                const decoded = await decodeWsMessage(event.data);
                responses.push(decoded.ok);
                if (responses.length === 1) {
                  ws.send(frame); // 第 2 次：同一 OTP 重放
                } else {
                  clearTimeout(timer);
                  try {
                    ws.close();
                  } catch (ignored) {
                    // 忽略
                  }
                  resolve({ firstOk: responses[0], secondOk: responses[1] });
                }
              };
              ws.onerror = () => {
                clearTimeout(timer);
                resolve({ firstOk: null, secondOk: null, error: 'ws error' });
              };
            });
          })();

          // validateAndConsumeOTP：有效 → true，再消费 → false
          const validateOtp = await DataChannel.createWsOtp();
          const validateFirst = await DataChannel.validateAndConsumeOTP({ otp: validateOtp.otp });
          const validateAgain = await DataChannel.validateAndConsumeOTP({ otp: validateOtp.otp });

          // 批量 OTP：一次桥调用生成 8 个，且互不重复
          const batchWs = await DataChannel.createWsOtps({ count: 8 });
          const batchUnique = batchWs.count === 8 && new Set(batchWs.otps).size === 8;

          // 用其中 2 个发消息（同步拼包）→ 批量解码（先验证、后交付）
          const batchPool = new WsOtpPool(batchWs.otps.slice(0, 2));
          const batchHandshake = await DataChannel.createWsOtp();
          const batchResponses = await (async () => {
            const socket = new WebSocket(wsInfo.wsUrl, [`otp.${batchHandshake.otp}`]);
            socket.binaryType = 'arraybuffer';
            const payloads = [new Uint8Array([1, 2, 3]), new Uint8Array([4, 5, 6])];
            const received = [];
            return await new Promise((resolve) => {
              const timer = setTimeout(() => {
                try {
                  socket.close();
                } catch (ignored) {
                  // 忽略
                }
                resolve({ received });
              }, 6000);
              socket.onopen = () => {
                for (const item of payloads) {
                  socket.send(encodeWsMessageWithOtp(batchPool.take(), item));
                }
              };
              socket.onmessage = (event) => {
                received.push(event.data);
                if (received.length === payloads.length) {
                  clearTimeout(timer);
                  try {
                    socket.close();
                  } catch (ignored) {
                    // 忽略
                  }
                  resolve({ received });
                }
              };
              socket.onerror = () => {
                clearTimeout(timer);
                resolve({ received });
              };
            });
          })();
          const batchDecoded = batchResponses.received.length
            ? await decodeWsMessagesBatch(batchResponses.received)
            : [];
          const batchAllOk = batchDecoded.length === 2 && batchDecoded.every((item) => item.ok === true);
          // 服务端 nonce 已被批量解码消费 → 再次批量验证应全部 false
          const extractOtp = (buf) => {
            const bytes = new Uint8Array(buf);
            const separator = bytes.indexOf(0);
            return separator > 0 ? new TextDecoder().decode(bytes.subarray(0, separator)) : null;
          };
          const batchNonces = batchResponses.received.map(extractOtp).filter(Boolean);
          const batchReplay = await DataChannel.validateAndConsumeOtps({ otps: batchNonces });
          const batchReplayAllFalse = batchReplay.results.length > 0 && batchReplay.results.every((v) => v === false);

          // HTTP 批量 OTP：生成 3 个，第一个可用
          const batchHttp = await DataChannel.createHttpOtps({ count: 3, scope: 'read', path: 'selftest/test.bin' });
          const batchHttpUse = await fetchT(
            `${http.baseUrl}/download?path=selftest/test.bin&otp=${batchHttp.otps[0]}`,
          );

          const revoked = await DataChannel.revokeAllOtps();
          const stopHttp = await DataChannel.stopHttp();
          const stopWs = await DataChannel.stopWebSocket();

          results.dataChannel = {
            httpRunning: http.running,
            httpPort: http.port,
            wsRunning: wsInfo.running,
            wsPort: wsInfo.port,
            healthOk: health.ok === true,
            uploadBytes: upload.bytes,
            downloadedBytes: downloadedBytes.byteLength,
            same,
            replayWriteStatus: replayWriteRes.status,
            replayReadStatus: replayReadRes.status,
            wrongPathStatus: wrongPathRes.status,
            writeWithReadStatus: writeWithReadRes.status,
            reusableWriteRejected,
            traversalRejected,
            mediaFirstStatus: mediaFirst.status,
            mediaSecondStatus: mediaSecond.status,
            mediaAfterRevokeStatus: mediaAfterRevoke.status,
            noOtpStatus: noOtpRes.status,
            unknownStatus: unknownRes.status,
            removedInfoStatus: removedInfoRes.status,
            removedListStatus: removedListRes.status,
            removedFileStatus: removedFileRes.status,
            preflightStatus: preflightRes.status,
            rsC1Status: rsC1.status,
            rsC2Status: rsC2.status,
            rsBadOffsetStatus: rsBadOffset.status,
            rsBadOffsetCode: rsBadOffsetJson.code,
            rsBadOffsetSize: rsBadOffsetJson.size,
            rsStatusPartSize: rsStatus.partSize,
            rsFinBadStatus: rsFinBad.status,
            rsFinBadCode: rsFinBadJson.code,
            rsBeforeFinalStatus: rsBeforeFinal.status,
            rsC3Status: rsC3.status,
            rsFinStatus: rsFin.status,
            rsFinSize: rsFinJson.size,
            rsContentOk,
            rsHashStatus: rsHash.status,
            rsHashCode: rsHashJson.code,
            rsAppendStatus: rsAppend.status,
            rsAppendCode: rsAppendJson.code,
            rsAbortAborted: rsAbort.aborted,
            rsAbortExists: rsAbortStatus.exists,
            rsAbortDownloadStatus: rsAbortDownload.status,
            wsReplayRejected: wsReplay.rejected,
            wsNoOtpOk: wsNoOtp.ok,
            wsMessageReplayFirstOk: wsMessageReplay.firstOk,
            wsMessageReplaySecondOk: wsMessageReplay.secondOk,
            validateFirstValid: validateFirst.valid,
            validateAgainValid: validateAgain.valid,
            batchWsCount: batchWs.count,
            batchUnique,
            batchAllOk,
            batchReplayAllFalse,
            batchHttpCount: batchHttp.otps.length,
            batchHttpUseStatus: batchHttpUse.status,
            revokedCount: revoked.revoked,
            httpStopped: stopHttp.running === false,
            wsStopped: stopWs.running === false,
          };
          const dcOk = http.running === true && http.port > 0
            && wsInfo.running === true && wsInfo.port > 0
            && health.ok === true
            && upload.bytes === payload.byteLength
            && same
            && replayWriteRes.status === 404
            && replayReadRes.status === 404
            && wrongPathRes.status === 404
            && writeWithReadRes.status === 404
            && reusableWriteRejected === true
            && traversalRejected === true
            && mediaFirst.status === 200
            && mediaSecond.status === 200
            && mediaAfterRevoke.status === 404
            && noOtpRes.status === 404
            && unknownRes.status === 404
            && removedInfoRes.status === 404
            && removedListRes.status === 404
            && removedFileRes.status === 404
            && preflightRes.status === 204
            && rsC1.status === 200
            && rsC2.status === 200
            && rsBadOffset.status === 409
            && rsBadOffsetJson.code === 'E_OFFSET_MISMATCH'
            && rsBadOffsetJson.size === rsChunk * 2
            && rsStatus.partSize === rsChunk * 2
            && rsFinBad.status === 409
            && rsFinBadJson.code === 'E_SIZE_MISMATCH'
            && rsBeforeFinal.status === 404
            && rsC3.status === 200
            && rsFin.status === 200
            && rsFinJson.size === rsTotal
            && rsContentOk === true
            && rsHash.status === 409
            && rsHashJson.code === 'E_HASH_MISMATCH'
            && rsAppend.status === 400
            && rsAppendJson.code === 'E_APPEND_DEPRECATED'
            && rsAbortStatus.exists === false
            && rsAbortDownload.status === 404
            && wsResult.sameLength === true
            && wsResult.sameHead === true
            && wsResult.sameTail === true
            && wsReplay.rejected === true
            && wsNoOtp.ok === false
            && wsMessageReplay.firstOk === true
            && wsMessageReplay.secondOk === false
            && validateFirst.valid === true
            && validateAgain.valid === false
            && batchUnique === true
            && batchAllOk === true
            && batchReplayAllFalse === true
            && batchHttp.otps.length === 3
            && batchHttpUse.status === 200
            && stopHttp.running === false
            && stopWs.running === false;
          if (!dcOk) {
            results.ok = false;
            results.reason = `data-channel check failed: ${JSON.stringify(results.dataChannel)}`;
          }
        } catch (err) {
          results.ok = false;
          results.dataChannelError = String((err && err.message) || err);
          try {
            await DataChannel.stopHttp();
          } catch (ignored) {
            // 尽力停止
          }
          try {
            await DataChannel.stopWebSocket();
          } catch (ignored) {
            // 尽力停止
          }
        }

        // ForegroundState：状态结构 + 监听/解除
        try {
          const foregroundInitial = await ForegroundState.getState();
          const foregroundHandle = await ForegroundState.addListener('stateChanged', () => {});
          const foregroundHasHandle = !!foregroundHandle && typeof foregroundHandle.remove === 'function';
          await foregroundHandle.remove();
          results.foregroundState = { initial: foregroundInitial, hasHandle: foregroundHasHandle };
          const foregroundOk = !!foregroundInitial
            && typeof foregroundInitial.isActive === 'boolean'
            && typeof foregroundInitial.state === 'string'
            && typeof foregroundInitial.changedAt === 'number'
            && typeof foregroundInitial.backgroundDuration === 'number'
            && foregroundHasHandle;
          if (!foregroundOk) {
            results.ok = false;
            results.reason = `foreground-state check failed: ${JSON.stringify(results.foregroundState)}`;
          }
        } catch (err) {
          results.ok = false;
          results.foregroundStateError = String((err && err.message) || err);
        }

        // NativeFiles：path-based 全链路（选择器需要用户操作，不放进自检）
        try {
          const { dir } = await this.nativeFilesSelfTest();
          results.nativeFiles = dir;
          const nfOk = dir.writeBytes > 0
            && dir.contentOk === true
            && dir.infoSize > 0
            && dir.listCount >= 1
            && dir.infoAfterRenameExists === true
            && dir.infoAfterDeleteExists === false;
          if (!nfOk) {
            results.ok = false;
            results.reason = `native-files check failed: ${JSON.stringify(results.nativeFiles)}`;
          }
        } catch (err) {
          results.ok = false;
          results.nativeFilesError = String((err && err.message) || err);
        }

        // 文件 URL（Capacitor 内置 convertFileSrc）：fetch / Range / img / script
        try {
          const url = await this.urlSchemeSelfTest();
          results.fileUrl = url;
          // 注意：Range 不参与判定——Capacitor 8.6.0 内置实现忽略 end（见 rangeEndIgnored），
          // 需要精确 Range 时应该用 data-channel 的 HTTP 或自己实现 scheme handler。
          const urlOk = url.fetchOk === true
            && url.imageOk === true
            && url.scriptOk === true;
          if (!urlOk) {
            results.ok = false;
            results.reason = `file-url check failed: ${JSON.stringify(results.fileUrl)}`;
          }
        } catch (err) {
          results.ok = false;
          results.fileUrlError = String((err && err.message) || err);
        }

        // CppBridge：C/C++ 链路（C++ 函数、纯 C 函数、native 内存句柄）
        try {
          const core = await CppBridge.getCoreInfo();
          const add = await CppBridge.add({ a: 7, b: 35 });
          const cAdd = await CppBridge.cAdd({ a: 7, b: 35 });
          const strlen = await CppBridge.cStrlen({ text: 'hello 中文' });
          const buffer = await this.cppBufferRun(4 * 1024 * 1024, 3);
          results.cppBridge = { core, add: add.result, cAdd: cAdd.result, strlen: strlen.length, buffer };
          const cppOk = core.loaded === true
            && add.result === 42
            && cAdd.result === 42
            && strlen.length === 12
            && buffer.sum === 3145728
            && buffer.statsAfter && buffer.statsAfter.count === 0;
          if (!cppOk) {
            results.ok = false;
            results.reason = `cpp-bridge check failed: ${JSON.stringify(results.cppBridge)}`;
          }
        } catch (err) {
          results.ok = false;
          results.cppBridgeError = String((err && err.message) || err);
        }

        // WebViewToEdge：开 → 读安全区 → 关
        const toEdgeOn = await WebViewToEdge.setEnabled({ enabled: true });
        await new Promise((resolve) => setTimeout(resolve, 300));
        const toEdgeInsets = await WebViewToEdge.getInsets();
        await WebViewToEdge.setEnabled({ enabled: false });
        results.toEdge = { state: toEdgeOn, insets: toEdgeInsets };
        if (!toEdgeOn.enabled || typeof toEdgeInsets.top !== 'number') {
          results.ok = false;
          results.reason = `webview-to-edge failed: ${JSON.stringify(results.toEdge)}`;
        }

        // Fullscreen：进入 → 检查 → 监听/解除 → 退出
        // （放最后，避免隐藏系统栏影响前面的断言；无论成败都退出全屏）
        try {
          const fsOn = await Fullscreen.enable();
          await new Promise((resolve) => setTimeout(resolve, 400));
          const fsState = await Fullscreen.isFullscreen();
          const fsHandle = await Fullscreen.addListener('fullscreenChanged', () => {});
          const fsHasHandle = !!fsHandle && typeof fsHandle.remove === 'function';
          await fsHandle.remove();
          const fsOff = await Fullscreen.disable();
          await new Promise((resolve) => setTimeout(resolve, 400));
          results.fullscreen = { on: fsOn, state: fsState, off: fsOff, hasHandle: fsHasHandle };
          const fsOk = !!fsOn && fsOn.fullscreen === true
            && !!fsState && fsState.fullscreen === true
            && !!fsOff && fsOff.fullscreen === false
            && fsHasHandle;
          if (!fsOk) {
            results.ok = false;
            results.reason = `fullscreen check failed: ${JSON.stringify(results.fullscreen)}`;
          }
        } catch (err) {
          results.ok = false;
          results.fullscreenError = String((err && err.message) || err);
        }
      } catch (err) {
        results.ok = false;
        results.error = String((err && err.message) || err);
      }

      window.__pluginTestResult = results;
      this.print('AUTOTEST 结果', results);
      console.log(`PLUGIN_AUTOTEST_RESULT ${JSON.stringify(results)}`);
    },

    async runEcho() {
      try {
        const res = await NativeBasics.echo({ value: `hello from ${this.platform}` });
        this.print('echo 成功', res);
      } catch (err) {
        this.error('echo', err);
      }
    },

    async runToast() {
      try {
        await NativeBasics.showToast({ text: '来自原生 Toast 的问候', duration: 'short' });
        this.print('showToast 成功', { text: '来自原生 Toast 的问候' });
      } catch (err) {
        this.error('showToast', err);
      }
    },

    async loadDeviceInfo() {
      try {
        const info = await NativeBasics.getDeviceInfo();
        this.print('getDeviceInfo 成功', info);
      } catch (err) {
        this.error('getDeviceInfo', err);
      }
    },

    async applyColor(color) {
      try {
        const res = await NativeStatusBar.setBackgroundColor({ color });
        this.print(`setBackgroundColor ${color}`, res);
      } catch (err) {
        this.error('setBackgroundColor', err);
      }
    },

    async applyStyle(style) {
      try {
        await NativeStatusBar.setStyle({ style });
        const info = await NativeStatusBar.getInfo();
        this.print(`setStyle ${style}`, info);
      } catch (err) {
        this.error('setStyle', err);
      }
    },

    async loadStatusBarInfo() {
      try {
        const info = await NativeStatusBar.getInfo();
        this.print('getInfo', info);
      } catch (err) {
        this.error('getInfo', err);
      }
    },

    async applyTheme(theme) {
      try {
        const info = await SetForceDark.setTheme({ theme });
        this.print(`setTheme ${theme}`, {
          ...info,
          dataTheme: document.documentElement.getAttribute('data-theme'),
          prefersDark: window.matchMedia('(prefers-color-scheme: dark)').matches,
        });
      } catch (err) {
        this.error('SetForceDark.setTheme', err);
      }
    },

    async toggleForceDark(enabled) {
      try {
        await SetForceDark.setForceDark({ enabled });
        const info = await SetForceDark.getInfo();
        this.print(`setForceDark ${enabled}`, info);
      } catch (err) {
        this.error('SetForceDark.setForceDark', err);
      }
    },

    async loadDarkInfo() {
      try {
        const info = await SetForceDark.getInfo();
        this.print('SetForceDark.getInfo', {
          ...info,
          dataTheme: document.documentElement.getAttribute('data-theme'),
          prefersDark: window.matchMedia('(prefers-color-scheme: dark)').matches,
        });
      } catch (err) {
        this.error('SetForceDark.getInfo', err);
      }
    },

    async loadSystemInfo() {
      try {
        const [screen, viewport, safeArea, statusBar, navigationBar, cutout, system, webView, memory, storage, battery, network, app] = await Promise.all([
          SystemInfo.getScreenInfo(),
          SystemInfo.getViewport(),
          SystemInfo.getSafeAreaInsets(),
          SystemInfo.getStatusBarInfo(),
          SystemInfo.getNavigationBarInfo(),
          SystemInfo.getCutoutInfo(),
          SystemInfo.getSystemInfo(),
          SystemInfo.getWebViewInfo(),
          SystemInfo.getMemoryInfo(),
          SystemInfo.getStorageInfo(),
          SystemInfo.getBatteryInfo(),
          SystemInfo.getNetworkInfo(),
          SystemInfo.getAppInfo(),
        ]);
        this.print('SystemInfo 成功', {
          screen, viewport, safeArea, statusBar, navigationBar, cutout,
          system, webView, memory, storage, battery, network, app,
        });
      } catch (err) {
        this.error('SystemInfo', err);
      }
    },

    async fullscreenEnter() {
      try {
        const state = await Fullscreen.enable();
        this.print('Fullscreen.enable 成功', state);
      } catch (err) {
        this.error('Fullscreen.enable', err);
      }
    },

    async fullscreenExit() {
      try {
        const state = await Fullscreen.disable();
        this.print('Fullscreen.disable 成功', state);
      } catch (err) {
        this.error('Fullscreen.disable', err);
      }
    },

    async fullscreenToggle() {
      try {
        const state = await Fullscreen.toggle();
        this.print('Fullscreen.toggle 成功', state);
      } catch (err) {
        this.error('Fullscreen.toggle', err);
      }
    },

    async loadFullscreenState() {
      try {
        const state = await Fullscreen.isFullscreen();
        this.print('Fullscreen.isFullscreen 成功', state);
      } catch (err) {
        this.error('Fullscreen.isFullscreen', err);
      }
    },

    async startFullscreenListen() {
      try {
        if (this.fullscreenHandle) {
          this.print('Fullscreen 监听', { status: '已在监听中，忽略重复开始' });
          return;
        }
        this.fullscreenHandle = await Fullscreen.addListener('fullscreenChanged', (state) => {
          this.print('fullscreenChanged 事件', state);
        });
        this.print('Fullscreen 监听已开始', { listening: true });
      } catch (err) {
        this.error('Fullscreen.addListener', err);
      }
    },

    async stopFullscreenListen() {
      try {
        if (!this.fullscreenHandle) {
          this.print('Fullscreen 监听', { status: '当前没有监听' });
          return;
        }
        await this.fullscreenHandle.remove();
        this.fullscreenHandle = null;
        this.print('Fullscreen 监听已取消', { listening: false });
      } catch (err) {
        this.error('Fullscreen.removeListener', err);
      }
    },

    async lockLandscape() {
      try {
        const state = await Rotation.lock({ orientation: 'landscape' });
        this.print('Rotation.lock(landscape) 成功', state);
      } catch (err) {
        this.error('Rotation.lock', err);
      }
    },

    async lockPortrait() {
      try {
        const state = await Rotation.lock({ orientation: 'portrait' });
        this.print('Rotation.lock(portrait) 成功', state);
      } catch (err) {
        this.error('Rotation.lock', err);
      }
    },

    async unlockRotation() {
      try {
        const state = await Rotation.unlock();
        this.print('Rotation.unlock 成功', state);
      } catch (err) {
        this.error('Rotation.unlock', err);
      }
    },

    async loadRotationLock() {
      try {
        const state = await Rotation.getLock();
        this.print('Rotation.getLock 成功', state);
      } catch (err) {
        this.error('Rotation.getLock', err);
      }
    },

    async enableKeyboardAvoid() {
      try {
        const state = await KeyboardAvoiding.enable();
        this.print('KeyboardAvoiding.enable 成功', state);
      } catch (err) {
        this.error('KeyboardAvoiding.enable', err);
      }
    },

    async disableKeyboardAvoid() {
      try {
        const state = await KeyboardAvoiding.disable();
        this.print('KeyboardAvoiding.disable 成功', state);
      } catch (err) {
        this.error('KeyboardAvoiding.disable', err);
      }
    },

    async loadKeyboardState() {
      try {
        const state = await KeyboardAvoiding.getState();
        this.print('KeyboardAvoiding.getState 成功', state);
      } catch (err) {
        this.error('KeyboardAvoiding.getState', err);
      }
    },

    async startKeyboardListen() {
      try {
        if (this.keyboardHandle) {
          this.print('KeyboardAvoiding 监听', { status: '已在监听中，忽略重复开始' });
          return;
        }
        this.keyboardHandle = await KeyboardAvoiding.addListener('keyboardChanged', (state) => {
          this.print('keyboardChanged 事件', state);
        });
        this.print('KeyboardAvoiding 监听已开始', { listening: true });
      } catch (err) {
        this.error('KeyboardAvoiding.addListener', err);
      }
    },

    async stopKeyboardListen() {
      try {
        if (!this.keyboardHandle) {
          this.print('KeyboardAvoiding 监听', { status: '当前没有监听' });
          return;
        }
        await this.keyboardHandle.remove();
        this.keyboardHandle = null;
        this.print('KeyboardAvoiding 监听已停止', { listening: false });
      } catch (err) {
        this.error('KeyboardAvoiding.removeListener', err);
      }
    },

    async pickFiles() {
      try {
        const result = await NativeFiles.pickFiles({ multiple: true });
        this.print('NativeFiles.pickFiles 成功', result);
      } catch (err) {
        this.error('NativeFiles.pickFiles', err);
      }
    },

    async pickFolders() {
      try {
        const result = await NativeFiles.pickFolders();
        this.print('NativeFiles.pickFolders 成功', result);
      } catch (err) {
        this.error('NativeFiles.pickFolders', err);
      }
    },

    async probeNativeFiles() {
      try {
        const { dir } = await this.nativeFilesSelfTest();
        this.print('NativeFiles 自检成功', dir);
      } catch (err) {
        this.error('NativeFiles 自检', err);
      }
    },

    /**
     * path-based 全链路自检：在 App 私有目录里建目录 → 写 → 读 → info → 列目录 → 重命名 → 删除。
     * 不依赖系统选择器，可被 autotest 调用。
     */
    async nativeFilesSelfTest() {
      // 逐个尝试拿一个可写目录：某个 API 失败/返回 null 不影响后面的尝试
      // （也顺便验证"拆分后的单条路径 API"这个设计）
      let base = null;
      let baseSource = null;
      const candidates = ['getAppExternalFilesDir', 'getAppFilesDir', 'getAppDocumentsDir'];
      for (const name of candidates) {
        try {
          const result = await NativeFiles[name]();
          if (result && result.path) {
            base = result.path;
            baseSource = name;
            break;
          }
        } catch {
          // 忽略，继续试下一个
        }
      }
      if (!base) {
        throw new Error('no writable app directory available');
      }
      const root = `${base}/nf-selftest`;
      const file = `${root}/hello.txt`;
      const content = `hello from native-files ${Date.now()}`;

      await NativeFiles.createFolder({ path: root });
      const write = await NativeFiles.writeFile({
        path: file,
        base64: btoa(content),
        mode: 'overwrite',
      });
      const read = await NativeFiles.readFile({ path: file });
      const contentOk = atob(read.base64) === content;
      const info = await NativeFiles.getInfo({ path: file });
      const list = await NativeFiles.listFolder({ path: root });
      const renamed = await NativeFiles.renameFolder({ path: root, newName: 'nf-selftest2' });
      const newFile = `${renamed.path}/hello.txt`;
      const infoAfterRename = await NativeFiles.getInfo({ path: newFile });
      await NativeFiles.deleteFile({ path: newFile });
      await NativeFiles.deleteFolder({ path: renamed.path, recursive: true });
      const infoAfterDelete = await NativeFiles.getInfo({ path: newFile });

      return {
        dir: {
          base,
          baseSource,
          writeBytes: write.bytes,
          readBytes: read.bytes,
          contentOk,
          infoSize: info.size,
          listCount: list.entries.length,
          renamedPath: renamed.path,
          infoAfterRenameExists: infoAfterRename.exists,
          infoAfterDeleteExists: infoAfterDelete.exists,
        },
      };
    },

    // ================================================================ 文件 URL（convertFileSrc）

    /**
     * 准备两个测试文件（文本 + 1x1 PNG），放在"公共 Documents"（拿不到就退回 App 文档目录）。
     * 返回真实绝对路径，供 convertFileSrc 转换。
     */
    // ================================================================ CppBridge（C/C++ 模板）

    async cppCoreInfo() {
      try {
        const info = await CppBridge.getCoreInfo();
        this.print('CppBridge.getCoreInfo', info);
      } catch (err) {
        this.error('CppBridge.getCoreInfo', err);
      }
    },

    async cppAdd() {
      try {
        const result = await CppBridge.add({ a: 7, b: 35 });
        this.print('CppBridge.add（C++）', result);
      } catch (err) {
        this.error('CppBridge.add', err);
      }
    },

    async cppCAdd() {
      try {
        const result = await CppBridge.cAdd({ a: 7, b: 35 });
        this.print('CppBridge.cAdd（纯 C）', result);
      } catch (err) {
        this.error('CppBridge.cAdd', err);
      }
    },

    async cppCStrlen() {
      try {
        const result = await CppBridge.cStrlen({ text: 'hello 中文' });
        this.print('CppBridge.cStrlen（纯 C）', result);
      } catch (err) {
        this.error('CppBridge.cStrlen', err);
      }
    },

    /**
     * 内存块自检：alloc → fill(3) → sum → stats → free。
     * 4MB / 4 字节 = 1048576 个 int32，每个值 3 → sum 应为 3145728。
     */
    async cppBufferRun(size, value) {
      const started = performance.now();
      const buffer = await CppBridge.allocBuffer({ size });
      let fillMs = 0;
      let sumMs = 0;
      let sum = null;
      let statsWhileHeld = null;
      let freed = null;
      let statsAfter = null;
      try {
        const fillStarted = performance.now();
        await CppBridge.fillBuffer({ handle: buffer.handle, value });
        fillMs = Math.round((performance.now() - fillStarted) * 100) / 100;

        const sumStarted = performance.now();
        sum = (await CppBridge.sumBuffer({ handle: buffer.handle })).sum;
        sumMs = Math.round((performance.now() - sumStarted) * 100) / 100;

        statsWhileHeld = await CppBridge.getBufferStats();
      } finally {
        // 兜底：即使上面抛错也把句柄释放掉，避免泄漏
        freed = await CppBridge.freeBuffer({ handle: buffer.handle }).catch(() => null);
        statsAfter = await CppBridge.getBufferStats().catch(() => null);
      }
      return {
        size: buffer.size,
        handle: String(buffer.handle),
        value,
        sum,
        fillMs,
        sumMs,
        statsWhileHeld,
        freed,
        statsAfter,
        totalMs: Math.round((performance.now() - started) * 100) / 100,
      };
    },

    async cppBufferSelfTest() {
      try {
        const result = await this.cppBufferRun(4 * 1024 * 1024, 3);
        this.print('CppBridge 内存块自检（4MB）', result);
      } catch (err) {
        this.error('CppBridge 内存块自检', err);
      }
    },

    async cppBufferBigTest() {
      try {
        const result = await this.cppBufferRun(64 * 1024 * 1024, 1);
        this.print('CppBridge 64MB 计时', result);
      } catch (err) {
        this.error('CppBridge 64MB 计时', err);
      }
    },

    async cppBufferStats() {
      try {
        const stats = await CppBridge.getBufferStats();
        this.print('CppBridge 缓冲统计（count>0 说明有句柄没释放）', stats);
      } catch (err) {
        this.error('CppBridge 缓冲统计', err);
      }
    },

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

    async loadAppDirectories() {
      try {
        // 逐个调用：某个路径取不到（比如 iOS 没有 downloads）不会影响其它调用
        const names = [
          'getAppFilesDir',
          'getAppCacheDir',
          'getAppTempDir',
          'getAppExternalFilesDir',
          'getAppExternalCacheDir',
          'getAppDocumentsDir',
          'getSharedStorageDir',
          'getDownloadsDir',
          'getSharedDocumentsDir',
          'getPicturesDir',
          'getDcimDir',
          'getMoviesDir',
          'getMusicDir',
          'getAlarmsDir',
          'getNotificationsDir',
          'getPodcastsDir',
          'getRingtonesDir',
          'getAppDataDir',
          'getAppExternalRootDir',
          'getNativeLibDir',
          'getLibraryDir',
          'getIcloudDocumentsDir',
          'getAppInstallPath',
        ];
        const out = {};
        for (const name of names) {
          try {
            const result = await NativeFiles[name]();
            out[name] = result && result.path ? result.path : `null (${(result && result.reason) || 'n/a'})`;
          } catch (err) {
            out[name] = `ERROR: ${String((err && err.message) || err)}`;
          }
        }
        this.print('NativeFiles 路径一览（逐个 API 查询）', out);
      } catch (err) {
        this.error('NativeFiles 路径一览', err);
      }
    },

    async checkAllFilesAccess() {
      try {
        const result = await NativeFiles.checkAllFilesAccess();
        this.print('NativeFiles.checkAllFilesAccess 成功', result);
      } catch (err) {
        this.error('NativeFiles.checkAllFilesAccess', err);
      }
    },

    async requestAllFilesAccess() {
      try {
        const result = await NativeFiles.requestAllFilesAccess();
        this.print('NativeFiles.requestAllFilesAccess 成功', result);
      } catch (err) {
        this.error('NativeFiles.requestAllFilesAccess', err);
      }
    },

    async loadForegroundState() {
      try {
        const state = await ForegroundState.getState();
        this.print('ForegroundState.getState 成功', state);
      } catch (err) {
        this.error('ForegroundState.getState', err);
      }
    },

    async startForegroundStateListen() {
      try {
        if (this.foregroundStateHandle) {
          this.print('ForegroundState 监听', { status: '已在监听中，忽略重复开始' });
          return;
        }
        this.foregroundStateHandle = await ForegroundState.addListener('stateChanged', (state) => {
          this.print('stateChanged 事件', state);
        });
        this.print('ForegroundState 监听已开始', { listening: true });
      } catch (err) {
        this.error('ForegroundState.addListener', err);
      }
    },

    async stopForegroundStateListen() {
      try {
        if (!this.foregroundStateHandle) {
          this.print('ForegroundState 监听', { status: '当前没有监听' });
          return;
        }
        await this.foregroundStateHandle.remove();
        this.foregroundStateHandle = null;
        this.print('ForegroundState 监听已停止', { listening: false });
      } catch (err) {
        this.error('ForegroundState.removeListener', err);
      }
    },

    async ensureHttp() {
      const info = await DataChannel.getHttpInfo();
      if (info.running) {
        return info;
      }
      return await DataChannel.startHttp();
    },

    async ensureWs() {
      const info = await DataChannel.getWsInfo();
      if (info.running) {
        return info;
      }
      return await DataChannel.startWebSocket();
    },

    async refreshDataChannelInfo() {
      const [http, webSocket] = await Promise.all([
        DataChannel.getHttpInfo(),
        DataChannel.getWsInfo(),
      ]);
      this.dataChannelInfo = { http, webSocket };
      return this.dataChannelInfo;
    },

    async startHttpServer() {
      try {
        const info = await DataChannel.startHttp();
        await this.refreshDataChannelInfo();
        this.print('DataChannel.startHttp 成功', info);
      } catch (err) {
        this.error('DataChannel.startHttp', err);
      }
    },

    async stopHttpServer() {
      try {
        const info = await DataChannel.stopHttp();
        await this.refreshDataChannelInfo();
        this.print('DataChannel.stopHttp 成功', info);
      } catch (err) {
        this.error('DataChannel.stopHttp', err);
      }
    },

    async startWsServer() {
      try {
        const info = await DataChannel.startWebSocket();
        await this.refreshDataChannelInfo();
        this.print('DataChannel.startWebSocket 成功', info);
      } catch (err) {
        this.error('DataChannel.startWebSocket', err);
      }
    },

    async stopWsServer() {
      try {
        const info = await DataChannel.stopWebSocket();
        await this.refreshDataChannelInfo();
        this.print('DataChannel.stopWebSocket 成功', info);
      } catch (err) {
        this.error('DataChannel.stopWebSocket', err);
      }
    },

    async loadDataChannelInfo() {
      try {
        await this.refreshDataChannelInfo();
        this.print('DataChannel 读取信息成功', this.dataChannelInfo);
      } catch (err) {
        this.error('DataChannel 读取信息', err);
      }
    },

    async revokeAllDataOtps() {
      try {
        const result = await DataChannel.revokeAllOtps();
        this.print('DataChannel.revokeAllOtps 成功', result);
      } catch (err) {
        this.error('DataChannel.revokeAllOtps', err);
      }
    },

    async dataChannelUpload() {
      try {
        const http = await this.ensureHttp();
        const sizeMB = 10;
        const path = `test/upload-${sizeMB}mb.bin`;
        const bytes = new Uint8Array(sizeMB * 1024 * 1024);
        // 填充部分随机内容（避免全零被压缩/优化，同时保持生成速度）
        const filler = new Uint8Array(65536);
        crypto.getRandomValues(filler);
        for (let offset = 0; offset < bytes.length; offset += filler.length) {
          bytes.set(filler.subarray(0, Math.min(filler.length, bytes.length - offset)), offset);
        }
        // 写操作：通过桥拿一次性 OTP（抓包拿不到生成过程）
        const otp = await DataChannel.createHttpOtp({ scope: 'write', path });
        const startedAt = performance.now();
        const res = await fetch(`${http.baseUrl}/upload?path=${path}`, {
          method: 'PUT',
          headers: {
            Authorization: `OTP ${otp.otp}`,
            'Content-Type': 'application/octet-stream',
          },
          body: bytes,
        });
        const json = await res.json();
        const elapsedMs = performance.now() - startedAt;
        this.print('DataChannel 上传完成', {
          status: res.status,
          uploadedBytes: json.bytes,
          storedSize: json.size,
          elapsedMs: Math.round(elapsedMs),
          speedMBps: Math.round(((sizeMB * 1000) / Math.max(1, elapsedMs)) * 100) / 100,
        });
      } catch (err) {
        this.error('DataChannel 上传', err);
      }
    },

    async dataChannelUploadChunked() {
      try {
        const http = await this.ensureHttp();
        const totalMB = 100;
        const chunkMB = 8;
        const chunkSize = chunkMB * 1024 * 1024;
        const totalBytes = totalMB * 1024 * 1024;
        const path = 'test/100mb-resumable.bin';
        // 复用同一个缓冲区：JS 侧内存恒定（这是分块上传的核心优势）
        if (!this.dcChunkBuffer || this.dcChunkBuffer.byteLength !== chunkSize) {
          this.dcChunkBuffer = new Uint8Array(chunkSize);
          crypto.getRandomValues(this.dcChunkBuffer.subarray(0, 65536));
        }
        const startedAt = performance.now();

        // 1) 查询已有进度：上次中断留下的 .part 会自动续传
        const statusOtp = await DataChannel.createHttpOtp({ scope: 'write', path });
        const status = await (
          await fetch(`${http.baseUrl}/upload?path=${path}&status=1&otp=${statusOtp.otp}`)
        ).json();
        let uploaded = status.partExists && status.partSize > 0 && status.partSize < totalBytes ? status.partSize : 0;
        const resumedFrom = uploaded;

        // 2) 按 offset 逐块上传（每块一个 write OTP）
        let chunks = 0;
        while (uploaded < totalBytes) {
          const size = Math.min(chunkSize, totalBytes - uploaded);
          const otp = await DataChannel.createHttpOtp({ scope: 'write', path });
          const res = await fetch(
            `${http.baseUrl}/upload?path=${path}&offset=${uploaded}&total=${totalBytes}`,
            {
              method: 'PUT',
              headers: { Authorization: `OTP ${otp.otp}` },
              body: this.dcChunkBuffer.subarray(0, size),
            },
          );
          if (!res.ok) {
            const err = await res.json().catch(() => ({}));
            throw new Error(`chunk@${uploaded} failed: HTTP ${res.status} ${err.code || ''} ${err.error || ''}`);
          }
          const json = await res.json();
          uploaded = json.partSize;
          chunks++;
        }

        // 3) finalize：校验总长度后原子落地（可选 &sha256=）
        const finOtp = await DataChannel.createHttpOtp({ scope: 'write', path });
        const finRes = await fetch(`${http.baseUrl}/upload?path=${path}&finalize=1&total=${totalBytes}`, {
          method: 'POST',
          headers: { Authorization: `OTP ${finOtp.otp}` },
        });
        const fin = await finRes.json();
        if (!finRes.ok) {
          throw new Error(`finalize failed: HTTP ${finRes.status} ${fin.code} ${fin.error}`);
        }
        const elapsedMs = performance.now() - startedAt;
        this.print('DataChannel 分块上传完成', {
          size: fin.size,
          chunks,
          chunkMB,
          resumedFrom,
          elapsedMs: Math.round(elapsedMs),
          speedMBps: Math.round(((totalMB * 1000) / Math.max(1, elapsedMs)) * 100) / 100,
        });
      } catch (err) {
        this.error('DataChannel 分块上传', err);
      }
    },

    async dataChannelUploadFile() {
      try {
        const file = this.$refs.dcFileInput && this.$refs.dcFileInput.files && this.$refs.dcFileInput.files[0];
        if (!file) {
          throw new Error('请先选择一个文件');
        }
        const http = await this.ensureHttp();
        const path = `test/${encodeURIComponent(file.name)}`;
        const otp = await DataChannel.createHttpOtp({ scope: 'write', path });
        const startedAt = performance.now();
        const res = await fetch(`${http.baseUrl}/upload?path=${path}`, {
          method: 'PUT',
          headers: { Authorization: `OTP ${otp.otp}` },
          body: file,
        });
        const json = await res.json();
        const elapsedMs = performance.now() - startedAt;
        this.print('DataChannel 上传文件完成', {
          name: file.name,
          fileSize: file.size,
          uploadedBytes: json.bytes,
          elapsedMs: Math.round(elapsedMs),
          speedMBps: Math.round(((file.size / 1024 / 1024 / Math.max(1, elapsedMs)) * 1000) * 100) / 100,
        });
      } catch (err) {
        this.error('DataChannel 上传文件', err);
      }
    },

    async dataChannelWebSocket() {
      try {
        const wsInfo = await this.ensureWs();
        const size = 1024 * 1024;
        const payload = new Uint8Array(size);
        // 注意：crypto.getRandomValues 单次上限 65536 字节，不能一次填充 1MB
        const filler = new Uint8Array(65536);
        crypto.getRandomValues(filler);
        for (let offset = 0; offset < size; offset += filler.length) {
          payload.set(filler.subarray(0, Math.min(filler.length, size - offset)), offset);
        }
        // 建连：一次性 WS OTP，通过子协议传递（不进 URL）
        const handshakeOtp = await DataChannel.createWsOtp();
        // 发送侧：批量取 4 个 OTP，避免每条消息一次桥调用
        const pool = await createWsOtpPool(4);
        const startedAt = performance.now();
        const result = await new Promise((resolve, reject) => {
          const ws = new WebSocket(wsInfo.wsUrl, [`otp.${handshakeOtp.otp}`]);
          ws.binaryType = 'arraybuffer';
          const timer = setTimeout(() => {
            try {
              ws.close();
            } catch (ignored) {
              // 忽略
            }
            reject(new Error('WebSocket 回显超时（15s）'));
          }, 15000);
          ws.onopen = () => {
            try {
              // 同步拼包（OTP 来自预取池，无桥往返）
              ws.send(encodeWsMessageWithOtp(pool.take(), payload));
            } catch (err) {
              clearTimeout(timer);
              reject(err);
            }
          };
          ws.onmessage = async (event) => {
            clearTimeout(timer);
            try {
              // 每条消息：解析 + validateAndConsumeOTP 校验
              const decoded = await decodeWsMessage(event.data);
              try {
                ws.close();
              } catch (ignored) {
                // 忽略
              }
              if (!decoded.ok) {
                reject(new Error(`消息校验失败: ${decoded.error}`));
                return;
              }
              const received = new Uint8Array(decoded.payload);
              resolve({
                sentBytes: size,
                receivedBytes: received.byteLength,
                sameLength: received.byteLength === size,
                sameHead: received[0] === payload[0],
                sameTail: received[size - 1] === payload[size - 1],
                roundTripMs: Math.round(performance.now() - startedAt),
              });
            } catch (err) {
              reject(err);
            }
          };
          ws.onerror = () => {
            clearTimeout(timer);
            reject(new Error('WebSocket 连接错误'));
          };
        });
        this.print('DataChannel WebSocket 回显完成', result);
      } catch (err) {
        this.error('DataChannel WebSocket', err);
      }
    },

    async startWithCustomRoot() {
      try {
        await DataChannel.stopHttp();
        const target =
          Capacitor.getPlatform() === 'ios'
            ? 'customData'
            : '/sdcard/Android/data/com.myapp.testionic/files/customData';
        const info = await DataChannel.startHttp({ rootDir: target });
        await this.refreshDataChannelInfo();
        this.print('DataChannel 自定义目录启动成功', info);
      } catch (err) {
        this.error('DataChannel 自定义目录启动', err);
      }
    },

    async dataChannelDownload() {
      try {
        const http = await this.ensureHttp();
        const path = 'test/upload-10mb.bin';
        // 读操作：一次性 OTP（媒体场景可用 reusable + 播放完 revoke）
        const otp = await DataChannel.createHttpOtp({ scope: 'read', path });
        const startedAt = performance.now();
        const res = await fetch(`${http.baseUrl}/download?path=${path}&otp=${otp.otp}`);
        if (!res.ok) {
          throw new Error(`download failed: HTTP ${res.status} ${await res.text()}`);
        }
        const buffer = await res.arrayBuffer();
        const elapsedMs = performance.now() - startedAt;
        this.print('DataChannel 下载完成', {
          status: res.status,
          bytes: buffer.byteLength,
          elapsedMs: Math.round(elapsedMs),
          speedMBps: Math.round(((buffer.byteLength / 1024 / 1024 / (elapsedMs || 1)) * 1000) * 100) / 100,
          head: Array.from(new Uint8Array(buffer).slice(0, 8)),
        });
      } catch (err) {
        this.error('DataChannel 下载', err);
      }
    },

    async loadRotation() {
      try {
        const state = await Rotation.getRotation();
        this.print('Rotation.getRotation 成功', state);
      } catch (err) {
        this.error('Rotation.getRotation', err);
      }
    },

    async startRotationListen() {
      try {
        if (this.rotationHandle) {
          this.print('Rotation 监听', { status: '已在监听中，忽略重复开始' });
          return;
        }
        this.rotationHandle = await Rotation.addListener('rotationChanged', (state) => {
          this.print('rotationChanged 事件', state);
        });
        this.print('Rotation 监听已开始', { listening: true });
      } catch (err) {
        this.error('Rotation.addListener', err);
      }
    },

    async stopRotationListen() {
      try {
        if (!this.rotationHandle) {
          this.print('Rotation 监听', { status: '当前没有监听' });
          return;
        }
        await this.rotationHandle.remove();
        this.rotationHandle = null;
        this.print('Rotation 监听已停止', { listening: false });
      } catch (err) {
        this.error('Rotation.removeListener', err);
      }
    },

    async setToEdge(enabled) {
      try {
        const state = await WebViewToEdge.setEnabled({ enabled });
        const cssTop = getComputedStyle(document.documentElement)
          .getPropertyValue('--safe-area-inset-top')
          .trim();
        this.print(`WebViewToEdge ${enabled ? '开启' : '关闭'}`, {
          ...state,
          cssVarTop: cssTop,
        });
      } catch (err) {
        this.error('WebViewToEdge.setEnabled', err);
      }
    },

    async loadToEdgeInfo() {
      try {
        const [insets, enabled] = await Promise.all([WebViewToEdge.getInsets(), WebViewToEdge.isEnabled()]);
        this.print('WebViewToEdge 状态', { ...enabled, insets });
      } catch (err) {
        this.error('WebViewToEdge.getInsets', err);
      }
    },
  },
};
</script>

<style scoped>
.plugin-test {
  max-width: 640px;
  margin: 0 auto;
  padding: 1rem;
  text-align: left;
}

.hint {
  color: var(--color-text);
  opacity: 0.7;
  font-size: 0.9rem;
}

.url-preview {
  display: block;
  width: 64px;
  height: 64px;
  image-rendering: pixelated;
  border: 1px solid rgba(127, 127, 127, 0.35);
  margin-top: 0.5rem;
}

.card {
  border: 1px solid rgba(127, 127, 127, 0.25);
  border-radius: 10px;
  padding: 0.75rem 1rem;
  margin: 1rem 0;
}

.card h2 {
  font-size: 1rem;
  margin: 0.25rem 0 0.75rem;
}

.swatches {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 0.5rem;
}

.swatch {
  border-radius: 8px;
  padding: 0.75rem 0.5rem;
  color: #fff;
  font-size: 0.75rem;
  text-align: center;
}

.actions {
  display: flex;
  gap: 0.5rem;
  flex-wrap: wrap;
  margin: 0.5rem 0;
}

.actions button {
  font-size: 0.85rem;
  padding: 0.35rem 0.7rem;
}

.kb-input {
  width: 100%;
  box-sizing: border-box;
  margin: 0.5rem 0 0.25rem;
  padding: 0.5rem 0.7rem;
  font-size: 1rem;
  border-radius: 6px;
  border: 1px solid rgba(127, 127, 127, 0.45);
  background: rgba(127, 127, 127, 0.12);
  color: inherit;
}

.output {
  background: rgba(127, 127, 127, 0.12);
  border-radius: 8px;
  padding: 1rem;
  min-height: 8rem;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 0.85rem;
}
</style>
