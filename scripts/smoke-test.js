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

  // 点击后等待输出里出现指定文本（用于上传/下载等耗时操作）
  const clickAndWait = async (label, expected, timeoutMs = 15000) => {
    const btn = [...document.querySelectorAll('button')].find((b) => b.textContent.trim() === label);
    if (!btn) throw new Error(`button not found: ${label}`);
    btn.click();
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      await sleep(300);
      const text = read();
      if (text.includes(expected) || text.includes('失败') || text.includes('Error')) {
        return text;
      }
    }
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

  // Rotation：读状态 → 开始监听 → 停止监听 → 方向锁定
  out.rotationInfo = await click('读取旋转状态');
  out.rotationListenStart = await click('开始监听');
  out.rotationListenStop = await click('停止监听');
  out.rotationLockLandscape = await click('锁定横屏');
  await sleep(700);
  out.rotationLockState = await click('读取锁定状态');
  out.rotationUnlock = await click('解锁（自动旋转）');
  out.rotationLockStateAfterUnlock = await click('读取锁定状态');

  // KeyboardAvoiding：读状态 → 开启避让 → 读状态 → 关闭避让 → 监听/停止监听
  out.keyboardState = await click('读取键盘状态');
  out.keyboardAvoidOn = await click('开启避让');
  out.keyboardStateOn = await click('读取键盘状态');
  out.keyboardAvoidOff = await click('关闭避让');
  out.keyboardListenStart = await click('监听键盘');
  out.keyboardListenStop = await click('停止键盘监听');

  // AppState：读状态 → 监听 → 停止监听
  out.appState = await click('读取应用状态');
  out.appStateListenStart = await click('监听状态变化');
  out.appStateListenStop = await click('停止状态监听');

  // DataChannel：启动 → 上传 10MB → 下载 → 列表 → WS 回显 → 权限检查 → 停止
  out.dataChannelStart = await click('启动通道');
  out.dataChannelInfo = await click('读取通道信息');
  out.dataChannelUpload = await clickAndWait('上传 10MB', '上传完成', 20000);
  out.dataChannelDownload = await clickAndWait('下载测试文件', '下载完成', 20000);
  out.dataChannelList = await click('列出文件');
  out.dataChannelWs = await clickAndWait('WS 回显 1MB', '回显完成', 20000);
  out.storageAccess = await click('检查存储权限');
  out.dataChannelStop = await click('停止通道');

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

  // Fullscreen：进入 → 读状态 → 退出 → 读状态 → 监听/取消（放最后，且保证退出全屏）
  out.fullscreenOn = await click('进入全屏');
  out.fullscreenStateOn = await click('读取全屏状态');
  out.fullscreenOff = await click('退出全屏');
  out.fullscreenStateOff = await click('读取全屏状态');
  out.fullscreenListenStart = await click('监听全屏变化');
  out.fullscreenListenStop = await click('取消全屏监听');

  const failures = [];
  for (const key of ['deviceInfo', 'echo', 'toast', 'statusBarSet', 'statusBarInfo', 'systemInfo', 'rotationInfo', 'rotationListenStart', 'rotationListenStop', 'rotationLockLandscape', 'rotationLockState', 'rotationUnlock', 'rotationLockStateAfterUnlock', 'keyboardState', 'keyboardAvoidOn', 'keyboardStateOn', 'keyboardAvoidOff', 'keyboardListenStart', 'keyboardListenStop', 'appState', 'appStateListenStart', 'appStateListenStop', 'dataChannelStart', 'dataChannelInfo', 'dataChannelUpload', 'dataChannelDownload', 'dataChannelList', 'dataChannelWs', 'storageAccess', 'dataChannelStop', 'fullscreenOn', 'fullscreenStateOn', 'fullscreenOff', 'fullscreenStateOff', 'fullscreenListenStart', 'fullscreenListenStop']) {
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
  if (!out.rotationLockState.includes('"locked": true') || !out.rotationLockState.includes('"orientation": "landscape"')) {
    failures.push('rotationLockLandscape');
  }
  if (!out.rotationLockStateAfterUnlock.includes('"orientation": "auto"')) {
    failures.push('rotationUnlock');
  }
  if (!out.dataChannelStart.includes('"running": true') || !out.dataChannelStart.includes('"port":')) {
    failures.push('dataChannelStart');
  }
  if (!out.appState.includes('"isActive"') || !out.appState.includes('"state": "active"')) {
    failures.push('appState');
  }
  if (!out.appStateListenStart.includes('监听已开始')) {
    failures.push('appStateListenStart');
  }
  if (!out.appStateListenStop.includes('监听已停止')) {
    failures.push('appStateListenStop');
  }
  if (!out.dataChannelUpload.includes('"uploadedBytes": 10485760')) {
    failures.push('dataChannelUpload');
  }
  if (!out.dataChannelDownload.includes('"bytes": 10485760')) {
    failures.push('dataChannelDownload');
  }
  if (!out.dataChannelList.includes('upload-10mb.bin')) {
    failures.push('dataChannelList');
  }
  if (!out.dataChannelWs.includes('"sameLength": true') || !out.dataChannelWs.includes('"sameTail": true')) {
    failures.push('dataChannelWebSocket');
  }
  if (!out.storageAccess.includes('"granted"')) {
    failures.push('storageAccess');
  }
  if (!out.dataChannelStop.includes('"running": false')) {
    failures.push('dataChannelStop');
  }
  if (!out.keyboardState.includes('"visible": false') || !out.keyboardState.includes('"autoAvoid": false')) {
    failures.push('keyboardState');
  }
  if (!out.keyboardStateOn.includes('"autoAvoid": true')) {
    failures.push('keyboardAvoidOn');
  }
  if (!out.keyboardListenStart.includes('监听已开始')) {
    failures.push('keyboardListenStart');
  }
  if (!out.keyboardListenStop.includes('监听已停止')) {
    failures.push('keyboardListenStop');
  }
  if (!out.fullscreenStateOn.includes('"fullscreen": true')) {
    failures.push('fullscreenOn');
  }
  if (!out.fullscreenStateOff.includes('"fullscreen": false')) {
    failures.push('fullscreenOff');
  }
  if (!out.fullscreenListenStart.includes('监听已开始')) {
    failures.push('fullscreenListenStart');
  }
  if (!out.fullscreenListenStop.includes('监听已取消')) {
    failures.push('fullscreenListenStop');
  }

  out.dataTheme = dataTheme;
  return { ok: failures.length === 0, failures, out };
})();
