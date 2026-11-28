#!/usr/bin/env ruby
# 从 iSH-ARM64 的 Xcode 工程里抽出"编译这个引擎需要哪些源文件/头文件路径"。
#
# 为什么需要它：iSH-ARM64 只提供 App target（`iSH-ARM64`），我们想要的是可以链进
# Capacitor App 的静态库，所以只能借它的 source list / header search paths 自己拼。
#
# 用法：ruby ish-extract-sources.rb <ish-arm64 dir> <out sources.txt> <out settings.sh>
require 'xcodeproj'
require 'pathname'

ish_dir = Pathname.new(ARGV[0] or abort('usage: ish-extract-sources.rb <ish-arm64> <sources.txt> <settings.sh>')).expand_path
sources_out = ARGV[1] or abort('missing sources output path')
settings_out = ARGV[2] or abort('missing settings output path')

project = Xcodeproj::Project.open((ish_dir + 'iSH.xcodeproj').to_s)

# 优先用 ARM64 客户机后端那个 target；找不到就退回 iSH 的默认 target。
names = ['iSH-ARM64', 'iSH', 'App', 'iSH-Linux']
target = names.lazy.map { |n| project.targets.find { |t| t.name == n } }.find(&:itself)
abort "找不到 iSH 的 target（现有：#{project.targets.map(&:name).join(', ')}）" unless target

rel = ->(path) { Pathname.new(path).expand_path.relative_path_from(ish_dir).to_s }
sources = target.source_build_phase.files.filter_map do |build_file|
  ref = build_file.file_ref
  next nil if ref.nil?
  next nil if ref.source_tree == 'DEVELOPER_DIR'
  path = ref.real_path.to_s rescue nil
  next nil if path.nil? || !path.start_with?(ish_dir.to_s)
  next nil unless path =~ /\.(c|m|mm|cc|cpp|S)$/
  # App 自己的 main 不要（我们的宿主 App 有自己的入口）
  next nil if File.basename(path) == 'main.m'
  rel.(path)
end.uniq

abort '源文件清单为空，解析失败' if sources.empty?

File.write(sources_out, sources.join("\n") + "\n")

# 头文件搜索路径从 target 的 build settings（含 baseConfiguration 的 xcconfig）里取
settings = target.build_settings('Debug')
inherited = settings['HEADER_SEARCH_PATHS'] || []
inherited = [inherited] unless inherited.is_a?(Array)
paths = inherited.map { |p| p.to_s.gsub('$(SRCROOT)', '').gsub('$(inherited)', '').strip }
                .reject(&:empty?)
paths.unshift('.') unless paths.include?('.')

File.write(settings_out, "ISH_HEADER_SEARCH_PATHS=\"#{paths.join(' ')}\"\n")
puts "target=#{target.name} sources=#{sources.length} header_paths=#{paths.length}"
