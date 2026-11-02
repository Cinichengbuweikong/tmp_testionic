import Foundation
import UIKit
import Capacitor

/**
 * SetForceDark 插件（iOS 侧，jsName = "SetForceDark"）。
 *
 * ============================ 一、iOS 这边其实没那么多事 ============================
 *
 * iOS 没有 Android 那种 forceDark / 算法反色，WKWebView 对 `prefers-color-scheme`
 * 的支持也是正常的：系统切深色，页面里的 `@media (prefers-color-scheme: dark)` 就会命中。
 *
 * 所以本插件在 iOS 上做的是"对齐两端行为"：
 *
 *   1. 把当前主题写进 `document.documentElement.dataset.theme` 和 `color-scheme`，
 *      这样同一份 `html[data-theme='dark']` 的 CSS 在 iOS/Android 都能用；
 *   2. setTheme / getTheme / themeChanged：强制亮暗通过 `overrideUserInterfaceStyle`
 *      实现（同时作用在 viewController 和 WKWebView 上，prefers-color-scheme 也会跟着变）；
 *   3. 用隐藏的 UIView 子类监听 `traitCollection` 变化，系统深浅色切换时派发事件。
 *
 * 注意：setForceDark 在 iOS 上是空操作（没有对应机制），保留它只是为了两端 API 一致。
 *
 * ============================ 二、JS 侧用法 ============================
 *
 * <pre>
 *   import { SetForceDark } from 'capacitor-set-force-dark';
 *
 *   await SetForceDark.setForceDark({ enabled: false }); // iOS 上是空操作
 *   await SetForceDark.setTheme({ theme: 'system' });
 *   const { isDark } = await SetForceDark.getTheme();
 *   const handle = await SetForceDark.addListener('themeChanged', (t) => console.log(t.isDark));
 * </pre>
 */
@objc(SetForceDarkPlugin)
public class SetForceDarkPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "SetForceDarkPlugin"
    public let jsName = "SetForceDark"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "setTheme", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getTheme", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setForceDark", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getInfo", returnType: CAPPluginReturnPromise)
    ]

    private var requestedTheme = "system"
    private var forceDarkEnabled = false
    private var themeInjected = false
    private var traitObserver: TraitObserverView?

    // MARK: - Lifecycle

    override public func load() {
        DispatchQueue.main.async { [weak self] in
            self?.installTraitObserver()
            // 页面可能还没加载完，稍后再注入一次
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
                self?.injectTheme()
            }
        }

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleDidBecomeActive),
            name: UIApplication.didBecomeActiveNotification,
            object: nil
        )
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    @objc private func handleDidBecomeActive() {
        injectTheme()
    }

    // MARK: - Plugin methods

    @objc func setTheme(_ call: CAPPluginCall) {
        let value = (call.getString("theme") ?? "system").lowercased()
        guard ["system", "light", "dark"].contains(value) else {
            call.reject("theme must be one of: system, light, dark")
            return
        }

        DispatchQueue.main.async { [weak self] in
            guard let self else {
                call.reject("Plugin deallocated")
                return
            }
            self.requestedTheme = value
            self.applyOverrideStyle()
            self.injectTheme()
            self.notifyThemeChanged()
            call.resolve(self.themeState())
        }
    }

    @objc func getTheme(_ call: CAPPluginCall) {
        DispatchQueue.main.async { [weak self] in
            guard let self else {
                call.reject("Plugin deallocated")
                return
            }
            self.injectTheme()
            call.resolve(self.themeState())
        }
    }

    @objc func setForceDark(_ call: CAPPluginCall) {
        // iOS 没有 forceDark / 算法反色，接受参数但不做任何事
        forceDarkEnabled = call.getBool("enabled") ?? false
        call.resolve()
    }

    @objc func getInfo(_ call: CAPPluginCall) {
        DispatchQueue.main.async { [weak self] in
            guard let self else {
                call.reject("Plugin deallocated")
                return
            }
            var info = self.themeState()
            info["forceDarkEnabled"] = false
            info["themeInjected"] = self.themeInjected
            // iOS 上取 UA 需要执行 JS（WKWebView 没有公开的 userAgent 属性），这里不折腾
            info["webViewUserAgent"] = self.bridge?.webView?.customUserAgent ?? ""
            call.resolve(info)
        }
    }

    // MARK: - Theme

    private func isDark() -> Bool {
        switch requestedTheme {
        case "dark":
            return true
        case "light":
            return false
        default:
            return currentTraitIsDark()
        }
    }

    private func currentTraitIsDark() -> Bool {
        if let webView = bridge?.webView {
            return webView.traitCollection.userInterfaceStyle == .dark
        }
        return bridge?.viewController?.traitCollection.userInterfaceStyle == .dark
    }

    private func themeState() -> [String: Any] {
        [
            "requestedTheme": requestedTheme,
            "isDark": isDark(),
            "isForced": requestedTheme != "system"
        ]
    }

    /// 强制亮/暗：同时作用在 VC 和 WKWebView 上，这样 prefers-color-scheme 也会被覆盖。
    private func applyOverrideStyle() {
        let style: UIUserInterfaceStyle
        switch requestedTheme {
        case "dark":
            style = .dark
        case "light":
            style = .light
        default:
            style = .unspecified
        }

        bridge?.viewController?.overrideUserInterfaceStyle = style
        bridge?.webView?.overrideUserInterfaceStyle = style
    }

    private func notifyThemeChanged() {
        notifyListeners("themeChanged", data: themeState())
    }

    // MARK: - Injection

    private func injectTheme() {
        guard let webView = bridge?.webView else {
            return
        }
        let theme = isDark() ? "dark" : "light"
        let js = Self.injectJS.replacingOccurrences(of: "__THEME__", with: theme)

        DispatchQueue.main.async {
            webView.evaluateJavaScript(js) { [weak self] _, _ in
                self?.themeInjected = true
            }
        }
    }

    // MARK: - Trait observer

    private func installTraitObserver() {
        guard traitObserver == nil, let host = bridge?.viewController?.view else {
            return
        }
        let observer = TraitObserverView()
        observer.isHidden = true
        observer.frame = .zero
        observer.onTraitChange = { [weak self] in
            guard let self, self.requestedTheme == "system" else { return }
            self.injectTheme()
            self.notifyThemeChanged()
        }
        host.addSubview(observer)
        traitObserver = observer
    }

    /// 隐藏的辅助视图：只用来接收 traitCollectionDidChange。
    private class TraitObserverView: UIView {
        var onTraitChange: (() -> Void)?

        override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
            super.traitCollectionDidChange(previousTraitCollection)
            if #available(iOS 13.0, *) {
                if previousTraitCollection?.userInterfaceStyle != traitCollection.userInterfaceStyle {
                    onTraitChange?()
                }
            }
        }
    }

    // MARK: - Injected script

    /// 与 Android 侧保持同一份脚本：写 data-theme / color-scheme，并把
    /// @media (prefers-color-scheme: dark) 的规则映射到 html[data-theme="dark"]。
    private static let injectJS = """
    (function () {
      var theme = '__THEME__';
      var root = document.documentElement;
      if (!root) { return; }
      root.setAttribute('data-theme', theme);
      root.style.colorScheme = theme;
      if (window.__capForceDarkPatched) { return; }
      var css = [];
      try {
        var sheets = document.styleSheets || [];
        for (var i = 0; i < sheets.length; i++) {
          var rules = null;
          try { rules = sheets[i].cssRules; } catch (e) { continue; }
          if (!rules) { continue; }
          for (var j = 0; j < rules.length; j++) {
            var rule = rules[j];
            if (rule.type !== 4) { continue; }
            var cond = rule.conditionText || (rule.media && rule.media.mediaText) || '';
            if (!/prefers-color-scheme\\s*:\\s*dark/i.test(cond)) { continue; }
            var inner = rule.cssRules || [];
            for (var k = 0; k < inner.length; k++) {
              var r = inner[k];
              if (!r.selectorText || !r.style) { continue; }
              var sels = r.selectorText.split(',').map(function (s) {
                s = s.trim();
                if (/^(:root|html)\\b/.test(s)) { return s.replace(/^(:root|html)/, 'html[data-theme="dark"]'); }
                return 'html[data-theme="dark"] ' + s;
              });
              css.push(sels.join(',') + '{' + r.style.cssText + '}');
            }
          }
        }
      } catch (e) { }
      if (!css.length) { return; }
      var style = document.createElement('style');
      style.id = 'cap-force-dark-patch';
      style.textContent = css.join('\\n');
      (document.head || document.documentElement).appendChild(style);
      window.__capForceDarkPatched = true;
    })();
    """
}
