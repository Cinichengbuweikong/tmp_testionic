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
