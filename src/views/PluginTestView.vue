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
import { NativeBasics } from 'capacitor-native-basics';
import { NativeStatusBar } from 'capacitor-native-statusbar';
import { SetForceDark } from 'capacitor-set-force-dark';

export default {
  data() {
    return {
      platform: Capacitor.getPlatform(),
      isNative: Capacitor.isNativePlatform(),
      output: '',
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
