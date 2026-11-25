// 生成 tauri-plugin-updater 用的 latest.json（纯 Node，无依赖）。
//
// 用法：
//   node scripts/gen-latest-json.mjs --dir dist --version 0.3.0 \
//     --notes-file .tmp/notes.md --out dist/latest.json \
//     --base-url https://github.com/y-cyfor/Pi-Carry/releases/download/v0.3.0
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
  --base-url <前缀>     必填，如 https://github.com/y-cyfor/Pi-Carry/releases/download/v0.3.0
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
// target 键与 tauri-plugin-updater 在存在多种包类型时追加 {bundle_type} 的规则一致。
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
  ['windows-x86_64-nsis', (v) => `pi-carry-windows-10-x64-${v}-setup.exe`],
  // macOS 就地更新包必须是 .app.tar.gz：updater 会解压替换 .app，DMG 只作为人工安装资产。
  ['macos-universal', (v) => `pi-carry-macos-11-universal-${v}.app.tar.gz`],
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
  // Linux 产物先按「架构 + 扩展名 + 版本」发现，缺/重都一次性报清楚，绝不猜。
  const discovered = new Map()
  const discoveryProblems = []
  for (const [target, arch, ext] of LINUX_INSTALLERS) {
    const suffix = `-${arch}-${version}.${ext}`
    const matches = existsSync(dir)
      ? readdirSync(dir).filter((name) => name.startsWith('pi-carry-linux-') && name.endsWith(suffix))
      : []
    if (matches.length === 1) {
      discovered.set(target, matches[0])
    } else if (matches.length === 0) {
      discoveryProblems.push(`未找到 pi-carry-linux-*-${arch}-${version}.${ext}（Linux ${arch} ${ext}）`)
    } else {
      // 同一架构出现两个发行版标签（例如 22.04 与 24.04）时不能替用户挑一个。
      discoveryProblems.push(`Linux ${arch} ${ext} 产物不唯一：${matches.join('、')}`)
    }
  }

  // 先做存在性检查，缺什么一次性列清楚，避免「跑到一半才发现缺 .sig」。
  const missing = [...discoveryProblems]
  const present = []
  for (const [target, nameOf] of FIXED_INSTALLERS) {
    const file = nameOf(version)
    if (!existsSync(join(dir, file))) missing.push(file)
    if (!existsSync(join(dir, `${file}.sig`))) missing.push(`${file}.sig`)
    present.push({ target, file })
  }
  for (const [target] of LINUX_INSTALLERS) {
    const file = discovered.get(target)
    if (!file) continue
    if (!existsSync(join(dir, `${file}.sig`))) missing.push(`${file}.sig`)
    present.push({ target, file })
  }
  if (!existsSync(join(dir, portableFile))) missing.push(portableFile)

  if (missing.length > 0) {
    if (!allowMissing) {
      console.error(`缺少 ${missing.length} 个期望文件（目录 ${dir}）：`)
      for (const name of missing) console.error(`  ${name}`)
      console.error('如确有平台本次不发布，加 --allow-missing 跳过。')
      process.exit(1)
    }
    console.error(`警告：--allow-missing，跳过 ${missing.length} 个缺失文件：`)
    for (const name of missing) console.error(`  ${name}`)
  }

  const platforms = {}
  const summary = []
  for (const { target, file } of present) {
    const sigPath = join(dir, `${file}.sig`)
    if (!existsSync(join(dir, file)) || !existsSync(sigPath)) continue
    const signature = readFileSync(sigPath, 'utf8').trim()
    platforms[target] = { signature, url: assetUrl(file) }
    summary.push({ target, file, sigLength: signature.length })
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

  for (const { target, file, sigLength } of summary) {
    console.log(`target ${target}  文件 ${file}  signature ${sigLength} 字节`)
  }
  for (const [target, entry] of Object.entries(portable)) {
    console.log(`portable ${target}  文件 ${portableFile}  sha256 ${entry.sha256.slice(0, 12)}`)
  }
  console.log(`已写出 ${out}（version ${version}，channel ${channel}，platforms ${Object.keys(platforms).length}）`)
}

main().catch((e) => fail(`生成失败：${e.message}`, 1))
