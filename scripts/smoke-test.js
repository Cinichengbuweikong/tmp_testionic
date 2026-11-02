// Smoke test executed inside the app's WebView (see scripts/verify-android.ps1).
// Drives the "Plugin Test" page through the DOM and returns the rendered results.
(async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const resultEl = () => document.querySelector('pre');
  const read = () => (resultEl() ? resultEl().textContent : '');

  const gotoPluginTest = async () => {
    const link = [...document.querySelectorAll('a')].find((a) => a.textContent.trim() === 'Plugin Test');
    if (link) {
      link.click();
    } else {
      location.hash = '#/plugin-test';
    }
    for (let i = 0; i < 40; i++) {
      await sleep(100);
      if (document.querySelector('pre') && document.querySelector('h1')?.textContent.includes('NativeBasics')) break;
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

  const failures = Object.entries(out)
    .filter(([, v]) => typeof v === 'string' && (v.includes('失败') || v.includes('Error')))
    .map(([k]) => k);

  return { ok: failures.length === 0, failures, out };
})();
