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

  // SystemInfo：读一次综合信息
  out.systemInfo = await click('读取系统信息');

  // Rotation：读状态 → 开始监听 → 停止监听
  out.rotationInfo = await click('读取旋转状态');
  out.rotationListenStart = await click('开始监听');
  out.rotationListenStop = await click('停止监听');

  // 暗色模式：强制暗色 → 检查 data-theme → 读取状态 → 恢复跟随系统
  await click('强制暗色');
  await sleep(400);
  const dataTheme = document.documentElement.getAttribute('data-theme');
  out.forceDarkInfo = await click('主题信息');
  await click('跟随系统');

  // WebViewToEdge：铺满整屏 → 读安全区 → 恢复默认
  await click('铺满整屏');
  await sleep(400);
  out.toEdgeInfo = await click('安全区信息');
  await click('恢复默认');

  // 复原，避免把设备状态栏留在橙色
  await click('透明');
  await click('默认');

  const failures = [];
  for (const key of ['deviceInfo', 'echo', 'toast', 'statusBarSet', 'statusBarInfo', 'systemInfo', 'rotationInfo', 'rotationListenStart', 'rotationListenStop']) {
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
  if (!out.toEdgeInfo.includes('"insets"') || !out.toEdgeInfo.includes('"top"')) {
    failures.push('webViewToEdgeInsets');
  }
  if (!out.systemInfo.includes('"platform": "android"')) {
    failures.push('systemInfoPlatform');
  }
  for (const key of ['"screen"', '"viewport"', '"safeArea"', '"statusBar"', '"navigationBar"', '"cutout"', '"system"', '"webView"', '"memory"', '"storage"', '"battery"', '"network"', '"app"']) {
    if (!out.systemInfo.includes(key)) {
      failures.push(`systemInfo:${key}`);
    }
  }
  if (!out.rotationInfo.includes('"orientation"') || !out.rotationInfo.includes('"rotationDegrees"')) {
    failures.push('rotationInfo');
  }
  if (!out.rotationListenStart.includes('监听已开始')) {
    failures.push('rotationListenStart');
  }
  if (!out.rotationListenStop.includes('监听已停止')) {
    failures.push('rotationListenStop');
  }

  out.dataTheme = dataTheme;
  return { ok: failures.length === 0, failures, out };
})();
