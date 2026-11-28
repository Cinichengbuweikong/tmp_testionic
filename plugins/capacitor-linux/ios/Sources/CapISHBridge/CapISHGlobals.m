/**
 * iSH-ARM64 的 app 层全局变量：`ISHShellExecutor` 会引用 `ProcessExitedNotification`
 * （声明在 `app/AppDelegate.h`，定义在 `app/AppDelegate.m`）。我们只链接引擎的静态库，
 * 不编译 iSH 的 App 层，所以这个符号必须由插件提供，否则链接会报
 *   "_ProcessExitedNotification", referenced from: +[ISHShellExecutor initialize]
 *
 * 名字和类型必须与 iSH 的头文件一致（`NSString *const`）。
 */
#if __has_include("ISHShellExecutor.h")
#import <Foundation/Foundation.h>

NSString *const ProcessExitedNotification = @"ProcessExitedNotification";
#endif
