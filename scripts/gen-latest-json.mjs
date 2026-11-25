// 生成 tauri-plugin-updater 用的 latest.json（纯 Node，无依赖）。
//
// target 键必须符合 Tauri updater 的解析规则：
//   1. 先找 `{os}-{arch}-{bundle_type}`，例如 windows-x86_64-nsis、linux-x86_64-deb；
//   2. 找不到再回退 `{os}-{arch}`，例如 windows-x86_64、darwin-aarch64。
// 各平台因此这样写：
//   - Windows：主用 `-nsis`，同时保留 `windows-x86_64` 回退（防 CLI/crate 版本错配
//     导致 bundle type 探测失败）；
//   - macOS：bundle type 是 `app`，Universal 包无法区分 Intel/Apple Silicon，
//     所以用 `darwin-x86_64` / `darwin-aarch64` 两个基础键指向同一个 `.app.tar.gz`；
//     旧版写死的 `macos-universal` 不是合法 target，updater 永远匹配不到，已删除。
//   - Linux：`linux-{arch}-deb` / `linux-{arch}-rpm`。
//
// 用法：
//   node scripts/gen-latest-json.mjs --dir dist --version 0.3.1 \
//     --notes-file .tmp/notes.md --out dist/latest.json \
//     --base-url https://github.com/y-cyfor/Pi-Carry/releases/download/v0.3.1
//
// 参数：
//   --dir          产物目录，默认 dist
//   --version      必填，x.y.z
//   --notes-file   可选，更新说明文件（保留换行）
//   --pub-date     可选，RFC3339，默认当前 UTC YYYY-MM-DDTHH:MM:SSZ
//   --out          可选，默认 <dir>/latest.json
//   --base-url     必填，资产下载前缀
//   --channel      stable | beta，默认 stable
//   --allow-missing 缺少资产或 .sig 时跳过并警告，而不是报错退出
//
// 退出码：0 成功，1 资产缺失/自检失败，2 参数或版本号非法。
import { createReadStream, existsSync, readdirSync, readFileSync, writeFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { join, resolve } from 'node:path'

// 允许预发布后缀（例如 0.4.0-beta.1）：beta 渠道需要它，stable 由 Rust 侧忽略预发布。
const SEMVER = /^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/
const RFC3339 = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})$/
const SHA256_HEX = /^[0-9a-f]{64}$/
const CHANNELS = new Set(['stable', 'beta'])

const fail = (msg, code = 1) => {
  console.error(msg)
  process.exit(code)
}

const HELP = `生成 tauri 自动更新清单 latest.json

用法:
  node scripts/gen-latest-json.mjs --version <x.y.z> --base-url <资产下载前缀> [其它选项]

参数:
  --dir <目录>          产物目录，默认 dist
  --version <x.y.z>     必填
  --notes-file <文件>   可选，更新说明（保留换行）
  --pub-date <RFC3339>  可选，默认当前 UTC YYYY-MM-DDTHH:MM:SSZ
  --out <文件>          可选，默认 <dir>/latest.json
  --base-url <前缀>     必填，如 https://github.com/y-cyfor/Pi-Carry/releases/download/v0.3.1
  --channel <通道>      stable | beta，默认 stable
  --allow-missing       缺资产或 .sig 时跳过并警告，而不是报错退出
  --help

退出码: 0 成功，1 资产缺失/自检失败，2 参数或版本号非法`

const argv = process.argv.slice(2)
if (argv.includes('--help') || argv.includes('-h')) {
  console.log(HELP)
  process.exit(0)
}
const opts = new Map()
let allowMissing = false
for (let i = 0; i < argv.length; i++) {
  const arg = argv[i]
  if (arg === '--allow-missing') {
    allowMissing = true
    continue
  }
  if (!arg.startsWith('--')) fail(`未知参数：${arg}`, 2)
  const key = arg.slice(2)
  const value = argv[i + 1]
  if (value === undefined || value.startsWith('--')) fail(`参数 ${arg} 缺少取值`, 2)
  if (opts.has(key)) fail(`参数重复：${arg}`, 2)
  opts.set(key, value)
  i++
}

const KNOWN = ['dir', 'version', 'notes-file', 'pub-date', 'out', 'base-url', 'channel']
for (const key of opts.keys()) {
  if (!KNOWN.includes(key)) fail(`未知参数：--${key}`, 2)
}

const version = opts.get('version')
if (!version) fail('缺少必填参数 --version', 2)
if (!SEMVER.test(version)) fail(`版本号非法：${version}（要求 x.y.z 或 x.y.z-prerelease，不接受 v 前缀）`, 2)

const baseUrlRaw = opts.get('base-url')
if (!baseUrlRaw) fail('缺少必填参数 --base-url', 2)
const baseUrl = baseUrlRaw.replace(/\/+$/, '')
if (!/^https?:\/\/[^/]+/.test(baseUrl)) fail(`--base-url 必须是 http(s) 地址：${baseUrlRaw}`, 2)

const channel = opts.get('channel') ?? 'stable'
if (!CHANNELS.has(channel)) fail(`--channel 只能是 stable 或 beta：${channel}`, 2)

const dir = resolve(opts.get('dir') ?? 'dist')
const out = resolve(opts.get('out') ?? join(dir, 'latest.json'))

const pubDate = opts.get('pub-date') ?? new Date().toISOString().replace(/\.\d{3}Z$/, 'Z')
if (!RFC3339.test(pubDate) || Number.isNaN(Date.parse(pubDate))) {
  fail(`--pub-date 不是合法 RFC3339：${pubDate}`, 2)
}

const notesFile = opts.get('notes-file')
const notes = notesFile === undefined ? '' : readFileSync(resolve(notesFile), 'utf8')

// 资产名固定规则：项目-系统-系统版本-架构-项目版本[后缀]。
// 一个物理文件可以服务多个 target 键（见文件头的 Tauri 解析规则），所以这里按
// 「文件 + targets」建模：文件与 .sig 各只检查一次，缺失项去重后再报。
//
// Linux 的「系统版本」不是常量：`scripts/package-linux.sh` 取构建机的
// `/etc/os-release` VERSION_ID（x64 = 22.04，arm64 = 24.04），所以这里按
// `pi-carry-linux-*-<arch>-<version>.<ext>` 在产物目录里发现，恰好一条才算数。
// 写死发行版号会在 runner/发行版变化时让发布聚合确定性失败（BUG-029）。
const LINUX_INSTALLERS = [
  ['linux-x86_64-deb', 'x64', 'deb'],
  ['linux-x86_64-rpm', 'x64', 'rpm'],
  ['linux-aarch64-deb', 'arm64', 'deb'],
  ['linux-aarch64-rpm', 'arm64', 'rpm'],
]
const FIXED_INSTALLERS = [
  {
    // updater 先按 `windows-x86_64-nsis` 查；基础键 `windows-x86_64` 是回退，
    // 两者必须指向同一个安装器和同一个 .sig。
    targets: ['windows-x86_64-nsis', 'windows-x86_64'],
    file: (v) => `pi-carry-windows-10-x64-${v}-setup.exe`,
  },
  {
    // macOS 就地更新包必须是 .app.tar.gz：updater 会解压替换 .app，DMG 只作为人工安装资产。
    // bundle type 是 `app`，最终回退到 darwin-{arch} 基础键；Universal 包两个键同 URL。
    targets: ['darwin-x86_64', 'darwin-aarch64'],
    file: (v) => `pi-carry-macos-11-universal-${v}.app.tar.gz`,
  },
]
const PORTABLE_TARGET = 'windows-x86_64'
const portableFile = `pi-carry-windows-10-x64-${version}.zip`

const assetUrl = (file) => `${baseUrl}/${encodeURIComponent(file)}`

const sha256 = (path) =>
  new Promise((ok, ko) => {
    const hash = createHash('sha256')
    createReadStream(path)
      .on('data', (chunk) => hash.update(chunk))
      .on('error', ko)
      .on('end', () => ok(hash.digest('hex')))
  })

const main = async () => {
  // Linux 产物先按「架构 + 扩展名 + 版本」发现：缺失与歧义分开收集。
  // 缺失可以按 --allow-missing 跳过；歧义（同一架构多个发行版标签）绝不能跳过，
  // 否则会静默生成少一个平台键的清单。
  const discovered = new Map()
  const duplicates = []
  const missing = []
  for (const [target, arch, ext] of LINUX_INSTALLERS) {
    const suffix = `-${arch}-${version}.${ext}`
    const matches = existsSync(dir)
      ? readdirSync(dir).filter((name) => name.startsWith('pi-carry-linux-') && name.endsWith(suffix))
      : []
    if (matches.length === 1) {
      discovered.set(target, matches[0])
    } else if (matches.length === 0) {
      missing.push(`未找到 pi-carry-linux-*-${arch}-${version}.${ext}（Linux ${arch} ${ext}）`)
    } else {
      // 同一架构出现两个发行版标签（例如 22.04 与 24.04）时不能替用户挑一个。
      duplicates.push(`Linux ${arch} ${ext} 产物不唯一：${matches.join('、')}`)
    }
  }

  // 歧义不是「缺资产」：--allow-missing 只允许跳过缺失，不能吞掉歧义。
  if (duplicates.length > 0) {
    console.error(`Linux 产物有 ${duplicates.length} 处不唯一，无法确定该发布哪个文件：`)
    for (const line of duplicates) console.error(`  ${line}`)
    process.exit(1)
  }

  // 先做存在性检查，缺什么一次性列清楚，避免「跑到一半才发现缺 .sig」。
  // 一个物理文件可能服务多个 target 键，因此文件与 .sig 各只检查一次，缺失项按文件去重。
  // 这里的 missing 已包含发现阶段的「未找到」，但不含歧义（歧义在上面无条件失败）。
  const assets = []
  for (const { targets, file: nameOf } of FIXED_INSTALLERS) {
    const file = nameOf(version)
    const hasFile = existsSync(join(dir, file))
    const hasSig = existsSync(join(dir, `${file}.sig`))
    if (!hasFile) missing.push(file)
    if (!hasSig) missing.push(`${file}.sig`)
    assets.push({ targets, file, ok: hasFile && hasSig })
  }
  for (const [target] of LINUX_INSTALLERS) {
    const file = discovered.get(target)
    if (!file) continue
    const hasSig = existsSync(join(dir, `${file}.sig`))
    if (!hasSig) missing.push(`${file}.sig`)
    assets.push({ targets: [target], file, ok: hasSig })
  }
  if (!existsSync(join(dir, portableFile))) missing.push(portableFile)
  const uniqueMissing = [...new Set(missing)]

  if (uniqueMissing.length > 0) {
    if (!allowMissing) {
      console.error(`缺少 ${uniqueMissing.length} 个期望文件（目录 ${dir}）：`)
      for (const name of uniqueMissing) console.error(`  ${name}`)
      console.error('如确有平台本次不发布，加 --allow-missing 跳过。')
      process.exit(1)
    }
    console.error(`警告：--allow-missing，跳过 ${uniqueMissing.length} 个缺失文件：`)
    for (const name of uniqueMissing) console.error(`  ${name}`)
  }

  const platforms = {}
  const summary = []
  const signatures = new Map()
  for (const { targets, file, ok } of assets) {
    if (!ok) continue
    // 同一物理文件的 .sig 只读一次，多个 target 共用同一份签名。
    if (!signatures.has(file)) {
      signatures.set(file, readFileSync(join(dir, `${file}.sig`), 'utf8').trim())
    }
    const signature = signatures.get(file)
    for (const target of targets) {
      platforms[target] = { signature, url: assetUrl(file) }
    }
    summary.push({ targets, file, sigLength: signature.length })
  }

  const portable = {}
  if (existsSync(join(dir, portableFile))) {
    portable[PORTABLE_TARGET] = {
      version,
      url: assetUrl(portableFile),
      sha256: await sha256(join(dir, portableFile)),
      notes,
    }
  } else if (!allowMissing) {
    fail(`缺少便携包：${portableFile}`, 1)
  }

  const manifest = { version, notes, pub_date: pubDate, platforms, portable }

  // 自检：写盘前把结构性问题挡在本地，别让坏清单进入 Release。
  const problems = []
  if (!SEMVER.test(manifest.version)) problems.push(`version 非法：${manifest.version}`)
  if (Object.keys(platforms).length === 0) problems.push('platforms 为空')
  for (const [target, entry] of Object.entries(platforms)) {
    if (!entry.signature) problems.push(`${target}: signature 为空`)
    if (!/^https?:\/\//.test(entry.url)) problems.push(`${target}: url 不是 http(s)：${entry.url}`)
  }
  for (const [target, entry] of Object.entries(portable)) {
    if (!/^https?:\/\//.test(entry.url)) problems.push(`portable ${target}: url 不是 http(s)：${entry.url}`)
    if (!SHA256_HEX.test(entry.sha256)) problems.push(`portable ${target}: sha256 不是 64 位小写 hex`)
  }
  if (problems.length > 0) {
    console.error('清单自检失败：')
    for (const line of problems) console.error(`  ${line}`)
    process.exit(1)
  }

  writeFileSync(out, `${JSON.stringify(manifest, null, 2)}\n`)

  for (const { targets, file, sigLength } of summary) {
    for (const target of targets) console.log(`target ${target}  文件 ${file}  signature ${sigLength} 字节`)
  }
  for (const [target, entry] of Object.entries(portable)) {
    console.log(`portable ${target}  文件 ${portableFile}  sha256 ${entry.sha256.slice(0, 12)}`)
  }
  console.log(`已写出 ${out}（version ${version}，channel ${channel}，platforms ${Object.keys(platforms).length}）`)
}

main().catch((e) => fail(`生成失败：${e.message}`, 1))
