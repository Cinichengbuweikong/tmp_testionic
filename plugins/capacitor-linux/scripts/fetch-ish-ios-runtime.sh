#!/usr/bin/env bash
#
# 把 iSH-ARM64（everettjf/dsh-ios 里的 ish-arm64/）构建成可以链接进 iOS App 的
# `CapISH.xcframework`（静态库 + 公共头文件）。
#
# ⚠️ 只能在 macOS 上跑（需要 Xcode / xcodebuild / ruby + xcodeproj gem）。
#    Windows/Linux 上没有任何办法产出 iOS 静态库，所以本脚本在非 macOS 会直接退出。
#
# 用法：
#   bash scripts/fetch-ish-ios-runtime.sh              # 全流程
#   ISH_DEBUG=1 bash scripts/fetch-ish-ios-runtime.sh  # 打印完整编译输出
#
# 产物（都 git-ignore）：
#   ios/ish-vendor/            iSH-ARM64 源码（按 ISH_COMMIT 固定）
#   ios/ish-build/             xcodebuild 中间产物
#   ios/CapISH.xcframework/    最终 framework（device + simulator）
#   ios/ish-headers/           ISHShellExecutor.h 等公共头（CapISHBridge 用它走真引擎路径）
#
# 完成之后：
#   1) 在宿主 App 的 target 里链接 ios/CapISH.xcframework（或把下面打印的 SPM 片段加进去）；
#   2) 保证 CapISHBridge.m 编译时能 #include 到 ISHShellExecutor.h
#      （把 ios/ish-headers 加进 HEADER_SEARCH_PATHS，或把 framework 的 Headers 暴露出来）；
#   3) 重新 `npx cap sync ios` 并构建 App。

set -euo pipefail

# 固定的引擎版本：换版本就改这里（并在真机上回归一次 exec / 退出码 / stderr 分流）。
ISH_REPO="${ISH_REPO:-https://github.com/everettjf/dsh-ios.git}"
ISH_COMMIT="${ISH_COMMIT:-HEAD}"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLUGIN_DIR="$(cd "$HERE/.." && pwd)"
VENDOR="$PLUGIN_DIR/ios/ish-vendor"
BUILD="$PLUGIN_DIR/ios/ish-build"
HEADERS="$PLUGIN_DIR/ios/ish-headers"
OUT="$PLUGIN_DIR/ios/CapISH.xcframework"

log()  { printf '\033[36m[ish]\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[ish]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[31m[ish]\033[0m %s\n' "$*" >&2; exit 1; }

if [[ "$(uname -s)" != "Darwin" ]]; then
  die "这个脚本只能在 macOS 上运行（当前 $(uname -s)）。iOS 静态库没有跨平台构建的办法。"
fi
command -v xcodebuild >/dev/null || die "找不到 xcodebuild，请装 Xcode 并 xcode-select"
command -v git >/dev/null || die "找不到 git"

# ---------------------------------------------------------------- 1. 拉源码
if [[ ! -d "$VENDOR/ish-arm64" ]]; then
  log "clone $ISH_REPO @ $ISH_COMMIT"
  mkdir -p "$VENDOR"
  git clone --depth 1 "$ISH_REPO" "$VENDOR"
  if [[ "$ISH_COMMIT" != "HEAD" ]]; then
    ( cd "$VENDOR" && git fetch --depth 1 origin "$ISH_COMMIT" && git checkout FETCH_HEAD )
  fi
else
  log "复用已有源码 $VENDOR（要更新先删掉它）"
fi
ISH_SRC="$VENDOR/ish-arm64"
[[ -d "$ISH_SRC" ]] || die "源码里没有 ish-arm64/ 目录：$ISH_SRC"
ISH_PBX="$ISH_SRC/iSH.xcodeproj/project.pbxproj"
[[ -f "$ISH_PBX" ]] || die "找不到 $ISH_PBX"

# ------------------------------------------------------- 2. 取源文件清单 + app 设置
# iSH 自己的 target 是 App；这里只借用它的 source list / header paths / framework
# 依赖，然后再拼一个 static library 出来。用 ruby + xcodeproj（CocoaPods 自带）读 pbxproj。
ruby -v >/dev/null || die "找不到 ruby（macOS 自带）"
if ! ruby -e 'require "xcodeproj"' >/dev/null 2>&1; then
  warn "没有 xcodeproj gem，尝试用 gem 安装到用户目录…"
  gem install --user-install xcodeproj --no-document >/dev/null || die "装不上 xcodeproj（可以用 sudo gem install xcodeproj）"
fi

mkdir -p "$BUILD" "$HEADERS"
log "解析 $ISH_PBX 的 iSH-ARM64 target"
ruby "$HERE/ish-extract-sources.rb" "$ISH_SRC" "$BUILD/sources.txt" "$BUILD/settings.sh" || die "解析 pbxproj 失败"
# shellcheck disable=SC1090
source "$BUILD/settings.sh"

SRC_COUNT=$(wc -l < "$BUILD/sources.txt" | tr -d ' ')
log "源文件 $SRC_COUNT 个；HEADER_SEARCH_PATHS=$ISH_HEADER_SEARCH_PATHS"
[[ "$SRC_COUNT" -gt 50 ]] || die "源文件数看起来不对（$SRC_COUNT），检查解析脚本"

# --------------------------------------------------------------- 3. 公共头文件
# CapISHBridge 只认 ISHShellExecutor.h；顺手把 app/ 下的头都拿过去，方便以后扩展。
cp "$ISH_SRC/app/ISHShellExecutor.h" "$HEADERS/" 2>/dev/null \
  || die "找不到 app/ISHShellExecutor.h（引擎版本变化？）"
log "头文件已复制到 $HEADERS"

# --------------------------------------------------------- 4. 按架构编译 libish.a
build_slice() {
  local sdk="$1" arch="$2" outdir="$BUILD/$sdk-$arch"
  mkdir -p "$outdir"
  log "编译 $sdk / $arch"
  local -a objs=()
  local -a flags=(
    -arch "$arch"
    -isysroot "$(xcrun --sdk "$sdk" --show-sdk-path)"
    -mios-version-min=15.0
    -fobjc-arc -fblocks
    -DGUEST_ARM64=1
    -O2 -g0
    -I"$ISH_SRC"
    -I"$ISH_SRC/app"
    -I"$ISH_SRC/deps/libarchive/libarchive"
  )
  # 引擎会 include 自己的 public header 目录（xcconfig 里的 HEADER_SEARCH_PATHS）
  IFS=' ' read -r -a extra_paths <<< "$ISH_HEADER_SEARCH_PATHS"
  for p in "${extra_paths[@]}"; do
    [[ -n "$p" ]] && flags+=(-I"$ISH_SRC/$p")
  done

  local idx=0
  while IFS= read -r rel; do
    [[ -z "$rel" ]] && continue
    local src="$ISH_SRC/$rel"
    [[ -f "$src" ]] || { warn "缺文件，跳过：$rel"; continue; }
    local obj="$outdir/$(printf '%05d' "$idx")_$(basename "${rel%.*}").o"
    idx=$((idx + 1))
    if [[ "${ISH_DEBUG:-0}" == "1" ]]; then
      xcrun --sdk "$sdk" clang "${flags[@]}" -c "$src" -o "$obj" || die "编译失败：$rel"
    else
      xcrun --sdk "$sdk" clang "${flags[@]}" -c "$src" -o "$obj" 2>"$outdir/$(basename "$rel").log" \
        || { cat "$outdir/$(basename "$rel").log" >&2; die "编译失败：$rel"; }
    fi
    objs+=("$obj")
  done < "$BUILD/sources.txt"

  log "归档 libish-$sdk-$arch.a（${#objs[@]} 个 .o）"
  xcrun --sdk "$sdk" libtool -static -o "$outdir/libish.a" "${objs[@]}" || die "libtool 失败"
  echo "$outdir/libish.a"
}

DEVICE_LIB="$(build_slice iphoneos arm64 | tail -1)"
SIM_LIB="$(build_slice iphonesimulator arm64 | tail -1)"

# ----------------------------------------------------------- 5. 组装 xcframework
log "组装 xcframework -> $OUT"
rm -rf "$OUT" "$BUILD/xcf"
mkdir -p "$BUILD/xcf/ios-arm64/Headers" "$BUILD/xcf/iossim-arm64/Headers"
cp "$DEVICE_LIB" "$BUILD/xcf/ios-arm64/libish.a"
cp "$SIM_LIB"    "$BUILD/xcf/iossim-arm64/libish.a"
cp "$HEADERS/ISHShellExecutor.h" "$BUILD/xcf/ios-arm64/Headers/" 2>/dev/null || true
cp "$HEADERS/ISHShellExecutor.h" "$BUILD/xcf/iossim-arm64/Headers/" 2>/dev/null || true

cat > "$BUILD/xcf/ios-arm64/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleIdentifier</key><string>com.testionic.capish.ios</string>
  <key>CFBundleName</key><string>CapISH</string>
  <key>CFBundlePackageType</key><string>XFWK</string>
  <key>XCFrameworkFormatVersion</key><string>1.0</string>
  <key>AvailableLibraries</key><array><dict>
    <key>LibraryIdentifier</key><string>ios-arm64</string>
    <key>LibraryPath</key><string>libish.a</string>
    <key>Architectures</key><array><string>arm64</string></array>
    <key>SupportedPlatform</key><string>ios</string>
  </dict></array>
</dict></plist>
PLIST
sed 's/com.testionic.capish.ios/com.testionic.capish.iossim/; s/ios-arm64/iossim-arm64/g; s#<string>ios</string>#<string>ios</string>#; s#<key>SupportedPlatform</key><string>ios</string>#<key>SupportedPlatform</key><string>ios</string><key>SupportedPlatformVariant</key><string>simulator</string>#' \
  "$BUILD/xcf/ios-arm64/Info.plist" > "$BUILD/xcf/iossim-arm64/Info.plist"

xcrun xcodebuild -create-xcframework \
  -library "$BUILD/xcf/ios-arm64/libish.a" -headers "$BUILD/xcf/ios-arm64/Headers" \
  -library "$BUILD/xcf/iossim-arm64/libish.a" -headers "$BUILD/xcf/iossim-arm64/Headers" \
  -output "$OUT" || die "xcodebuild -create-xcframework 失败"

log "完成：$OUT"
cat <<'NEXT'

下一步（一次性，改宿主 App 工程）：

  1) 把 ${PLUGIN_DIR}/ios/CapISH.xcframework 加进 App target（Frameworks/Libraries），
     或直接用 SPM 的话在 App 的 Package.swift 里加：
        .binaryTarget(name: "CapISH", path: "../../plugins/capacitor-linux/ios/CapISH.xcframework")
  2) App target 的 HEADER_SEARCH_PATHS 加上：
        $(SRCROOT)/../../plugins/capacitor-linux/ios/ish-headers
     （CapISHBridge.m 用 #include "ISHShellExecutor.h" 判断引擎是否存在）
  3) 需要链接的系统库（iSH 用到）：libarchive 已内置在 ish-arm64/deps，
     另外通常还要 -lz -lbz2 -llzma -lsqlite3 -framework Foundation。
  4) `npx cap sync ios` 后正常构建 App。
NEXT
