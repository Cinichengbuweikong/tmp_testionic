<template>
  <div class="plugin-test">
    <h1>NativeBasics 插件测试</h1>
    <p class="hint">当前平台：{{ platform }} · 原生可用：{{ isNative }}</p>

    <div class="actions">
      <button @click="runEcho">echo</button>
      <button @click="runToast">showToast</button>
      <button @click="loadDeviceInfo">getDeviceInfo</button>
    </div>

    <pre class="output">{{ output }}</pre>
  </div>
</template>

<script>
import { Capacitor } from '@capacitor/core';
import { NativeBasics } from 'capacitor-native-basics';

export default {
  data() {
    return {
      platform: Capacitor.getPlatform(),
      isNative: Capacitor.isNativePlatform(),
      output: '',
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

    /**
     * 自动化验证：CI（iOS 模拟器 / Android 真机）里跑，把结果同时写到页面和 console。
     * console 那一行会被 Capacitor 转发到原生日志，CI 可以直接 grep。
     */
    async runAutotest() {
      const results = { platform: this.platform };
      try {
        results.echo = await NativeBasics.echo({ value: 'autotest' });
        await NativeBasics.showToast({ text: 'autotest toast', duration: 'short' });
        results.toast = 'ok';
        results.deviceInfo = await NativeBasics.getDeviceInfo();
        results.ok = true;
      } catch (err) {
        results.ok = false;
        results.error = String((err && err.message) || err);
      }

      window.__pluginTestResult = results;
      this.print('AUTOTEST 结果', results);
      console.log(`PLUGIN_AUTOTEST_RESULT ${JSON.stringify(results)}`);
    },

    error(label, err) {
      this.print(`${label} 失败`, { message: String((err && err.message) || err) });
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

.actions {
  display: flex;
  gap: 0.75rem;
  flex-wrap: wrap;
  margin: 1rem 0;
}

.output {
  background: #f4f4f5;
  border-radius: 8px;
  padding: 1rem;
  min-height: 8rem;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 0.85rem;
}
</style>
