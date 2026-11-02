// Smoke test executed inside the app's WebView (see scripts/verify-android.ps1).
// Drives the "插件自检" page through the DOM and returns the rendered results.
(async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const read = () => {
    const el = document.querySelector('pre');
    return el ? el.textContent : '';
  };

  const gotoPluginTest = async () => {
    const link = [...document.querySelectorAll('a')].find((a) => a.textContent.trim() === 'Plugin Test');
    if (link) {
      link.click();
    } else {
      location.hash = '#/plugin-test';
    }
    for (let i = 0; i < 40; i++) {
      await sleep(100);
      const title = document.querySelector('h1');
      if (document.querySelector('pre') && title && title.textContent.includes('插件自检')) break;
    }
  };

  const click = async (label) => {
    const btn = [...document.querySelectorAll('button')].find((b) => b.textContent.trim() === label);
    if (!btn) throw new Error(`button not found: ${label}`);
    btn.click();
    await sleep(900);
    return read();
  };

  await gotoPluginTest();

  const out = {
    platform: (window.Capacitor && window.Capacitor.getPlatform && window.Capacitor.getPlatform()) || 'unknown',
    deviceInfo: await click('getDeviceInfo'),
    echo: await click('echo'),
    toast: await click('showToast'),
  };

  // 状态栏：设色 → 切暗色图标 → 读回
  out.statusBarSet = await click('橙 #FF5722');
  out.statusBarStyle = await click('暗色图标');
  out.statusBarInfo = await click('getInfo');

  // 暗色模式：强制暗色 → 检查 data-theme → 读取状态 → 恢复跟随系统
  await click('强制暗色');
  await sleep(400);
  const dataTheme = document.documentElement.getAttribute('data-theme');
  out.forceDarkInfo = await click('主题信息');
  await click('跟随系统');

  // 复原，避免把设备状态栏留在橙色
  await click('透明');
  await click('默认');

  const failures = [];
  for (const key of ['deviceInfo', 'echo', 'toast', 'statusBarSet', 'statusBarInfo']) {
    if (typeof out[key] === 'string' && (out[key].includes('失败') || out[key].includes('Error'))) {
      failures.push(key);
    }
  }
  if (!out.statusBarInfo.includes('#FFFF5722')) {
    failures.push('statusBarColor');
  }
  if (dataTheme !== 'dark') {
    failures.push(`setForceDark(data-theme=${dataTheme})`);
  }
  if (!out.forceDarkInfo.includes('requestedTheme') || !out.forceDarkInfo.includes('"isDark": true')) {
    failures.push('setForceDarkInfo');
  }

  out.dataTheme = dataTheme;
  return { ok: failures.length === 0, failures, out };
})();
