# 没有苹果设备，怎么做 iOS？

结论：**能，但本机（Windows）永远只能“写”不能“编译”**。iOS 的编译链（Xcode / iOS SDK）只在 macOS 上存在，这点绕不过去。
所以现实的做法是：在 Windows 上写代码 → 把编译/运行交给云端或借来的 Mac。

## 方案对比

| 方案 | 成本 | 能做什么 | 适合 |
| --- | --- | --- | --- |
| **GitHub Actions macOS runner**（推荐先用） | 公开仓库免费；私有仓库 2000 分钟/月（macOS 约 10 倍计费，≈200 分钟） | 编译、跑模拟器、截图、出包、签名上架 | 插件/App 的持续验证 |
| 云 Mac 按时租（MacinCloud、MacStadium、NoMac 等） | 按小时/按秒计费 | 完整 Xcode，可交互调试、连真机 | 首次调试、疑难问题 |
| 二手 Mac mini（M1/M2） | 一次性几千元 | 本地完整开发 | 长期认真做 iOS |
| 黑苹果 / 虚拟机装 macOS | 折腾成本高 | 违反 Apple EULA，不稳定 | ❌ 不推荐 |

> GitHub 的 macOS 分钟只对「标准 runner」免费；公开仓库不限量，私有仓库按 10 倍消耗额度。
> 参考：[GitHub Actions billing](https://docs.github.com/en/billing/concepts/product-billing/github-actions)、[free macOS minutes 说明](https://dev.to/maclessdev/github-actions-free-macos-minutes-explained-33p7)

## 云 Mac 报价对比（2026-11 查询，价格会变，以官网为准）

| 服务 | 计费 | 价格 | root/SSH | 特点 |
| --- | --- | --- | --- | --- |
| [NoMac](https://nomac.app/) | 按分钟 | **$0.90/小时**，$10 起充，余额不过期 | ✅ root + SSH/CLI/API/MCP | 为 AI agent 设计，Xcode 27 预装，开机会话 ~1 分钟，20GB 持久盘 |
| [MacinCloud](https://www.macincloud.com/pages/payg.html) | 预付 25 小时起 | **$1/小时**（或 $4/天） | ❌ 托管计划无 root（Dedicated 才有） | 老牌（2010 起），有新加坡/孟买机房，图形远程桌面 |
| [Scaleway](https://www.scaleway.com/en/hello-m1/) | 月付为主，支持按小时 | 约 €50/月（M1 Mac mini 独享） | ✅ | 欧洲机房，适合长期常开 |
| [MacStadium](https://www.macstadium.com/) | 月付 | $79/月起（M2 mini 独享） | ✅ | 企业级，适合团队 CI |

按需使用的花费（NoMac / MacinCloud 按小时价）：

| 每月使用时长 | 费用 |
| --- | --- |
| 5 小时 | $4.5 ~ $5 |
| 10 小时 | $9 ~ $10 |
| 20 小时 | $18 ~ $20（到这个量就该考虑月付了） |

> 注意：MacinCloud 的托管计划没有 root，装工具受限；如果希望「AI 直接 SSH 进去编译」，选 **NoMac** 这类给 root 的更省事。
> 中国到美国机房的图形桌面会有延迟，但纯命令行编译基本无感。

## 支付：只支付宝/微信怎么办

实话说：**NoMac、MacinCloud、Scaleway、MacStadium 都不支持支付宝/微信**，只收国际信用卡或 PayPal。
国内用户能走的路只有三条：

| 方式 | 做法 | 风险/成本 |
| --- | --- | --- |
| **淘宝/闲鱼租云 Mac** | 搜「云 Mac / Mac 云电脑 / Mac 远程」，支付宝直接付，常见几十到几百元/月 | 卖家鱼龙混杂、机器可能超售、**不要在上面登录 Apple ID 或存放证书** |
| **找人代付** | 朋友有 Visa/Master/PayPal，帮你充 $10（NoMac 约 11 小时） | 最省事，需信任关系 |
| **虚拟信用卡** | 2026 年法币充值的虚拟卡（野卡/Dupay 等）基本关停，剩下的多是 USDT 充值的 U 卡 | 需要加密货币，门槛和合规风险都高，不推荐 |

> 结论：如果短期只是偶尔验证，**先别花钱**，用下面的 GitHub Actions 方案；真要交互调试再考虑淘宝或代付。

## GitHub Actions 也能做「真实运行」验证（免费）

`build ≠ usable`，所以 `.github/workflows/ios.yml` 里除了编译，还会：

1. 用 `VITE_AUTOTEST=1` 构建前端 —— App 启动后自动跳到 `Plugin Test` 页；
2. 在 iOS 模拟器里安装并启动 App；
3. 页面自动执行 `echo` / `showToast` / `getDeviceInfo`；
4. 结果通过 `console.log('PLUGIN_AUTOTEST_RESULT {...}')` 输出，Capacitor 会把它转发到原生日志；
5. CI 用 `simctl launch --console-pty` 抓日志，找不到标记或 `"ok":true` 就**直接失败**；
6. 截图 + 完整日志作为 artifact 上传，可以直观看到模拟器里的界面。

也就是说：每次 push，你都能看到 App 在 iPhone 模拟器里真实跑起来的样子，以及插件调用是否成功，成本 0。

## 这个仓库里已经准备好的东西

- `ios/` 平台已生成（Capacitor 8 默认 **SPM**，不是 CocoaPods），插件已正确接入 `ios/App/CapApp-SPM/Package.swift`
- `ios/App/App.xcodeproj/xcshareddata/xcschemes/App.xcscheme`：手写的共享 scheme，保证命令行 `xcodebuild -scheme App` 能找到 scheme
- 插件的 iOS 实现：`plugins/capacitor-native-basics/ios/Sources/NativeBasicsPlugin/NativeBasicsPlugin.swift` + `Package.swift`
- CI 工作流：`.github/workflows/ios.yml`
  - 在 `macos-26`（Xcode 26，Capacitor 8 要求）上 `npm ci` → 构建 web → `cap sync ios` → `xcodebuild` 编译
  - 启动 iOS 模拟器、安装、拉起 App、截图，并把截图作为 artifact 上传
- 本机可跑的静态检查：`npm run verify:ios`
  - 校验 `Package.swift` 存在、`jsName` 与 JS `registerPlugin` 一致、TS 声明的方法与 Swift `pluginMethods` 一致、`CapApp-SPM` 是否引用了插件
  - **注意：这只是静态检查，不代表能编译**，真正的编译在 CI

## 你还需要做的

1. 把项目推到 GitHub（公开仓库最省事；私有仓库每月约 200 分钟 macOS 额度，够 20~40 次构建）。
2. 打开仓库的 Actions 页面，手动跑一次 `iOS` workflow。
3. 首次跑大概率会有编译错误（Swift 是“盲写”的，没有本地编译器）——把日志贴给我，我按报错修。

## 边界与后续

- **模拟器能验证**：编译、启动、插件调用、截图、console 日志——CI 里已经自动化，失败会直接红。
- **真机不能**：需要 Apple Developer Program（$99/年）+ 证书；有账号后可以继续用 CI 做 TestFlight 发布。
- **交互调试**：CI 不适合“看着屏幕点两下”的场景，需要云 Mac（按小时租）或借一台 Mac。
