// 版本单一来源同步脚本（纯 Node，无依赖）。
//
// 用法：
//   node scripts/set-version.mjs 0.3.0          # 把所有版本字段改成 0.3.0（Android versionCode +1）
//   node scripts/set-version.mjs --check        # 以 Cargo.toml workspace 版本为基准校验其它文件
//   node scripts/set-version.mjs --check 0.3.0  # 校验，并额外要求基准等于 0.3.0
//
// 退出码：0 成功/一致，1 校验不一致或锚点缺失，2 版本号非法或用法错误。
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'

// 允许预发布后缀，beta 渠道会用到（例如 0.4.0-beta.1）。
const SEMVER = /^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/
const root = resolve(import.meta.dirname, '..')

const CARGO = 'Cargo.toml'
const LOCK = 'Cargo.lock'
const TAURI = 'crates/desktop/tauri.conf.json'
const PS1 = 'scripts/package.ps1'
const GRADLE = 'android/app/build.gradle.kts'

const read = (p) => readFileSync(resolve(root, p), 'utf8')
const write = (p, text) => writeFileSync(resolve(root, p), text)

// 在文本里定位一个版本值，返回 { version, start, end }；start/end 是值本身的区间，
// 替换时只动这段字符，保证缩进、引号风格与文件末尾换行原样保留。
const locate = (text, re, label) => {
  const m = re.exec(text)
  if (!m) throw new Error(`找不到版本锚点：${label}`)
  const start = m.index + m[0].indexOf(m[1])
  return { version: m[1], start, end: start + m[1].length }
}

const replaceAt = (text, spot, value) => text.slice(0, spot.start) + value + text.slice(spot.end)

// Cargo.toml 的 [workspace.package] 段内的 version（不是 [package] 或依赖版本）。
const cargoSpot = (text) => {
  const header = '[workspace.package]'
  const h = text.indexOf(header)
  if (h < 0) throw new Error(`找不到版本锚点：${CARGO} 缺少 ${header}`)
  const bodyStart = h + header.length
  const after = text.slice(bodyStart)
  const next = after.search(/\n\[/)
  const bodyEnd = next < 0 ? text.length : bodyStart + next
  const body = text.slice(bodyStart, bodyEnd)
  const spot = locate(body, /^version\s*=\s*"([^"]*)"/m, `${CARGO} [workspace.package] version`)
  return { version: spot.version, start: bodyStart + spot.start, end: bodyStart + spot.end }
}

// tauri.conf.json 顶层 "version"：缩进固定两个空格，嵌套键缩进更深，因此该正则只命中顶层。
const tauriSpot = (text) =>
  locate(text, /^ {2}"version"\s*:\s*"([^"]*)"/m, `${TAURI} 顶层 version`)

// package.ps1 旧写法是字面量 $version = 'x.y.z'；改成从 Cargo.toml 派生后没有字面量可改，
// 此时返回 null（同步/校验都视为已一致），避免对派生实现误报“锚点缺失”。
const ps1Spot = (text) => {
  const m = /\$version\s*=\s*'([^']*)'/.exec(text)
  if (!m) return null
  const start = m.index + m[0].indexOf(m[1])
  return { version: m[1], start, end: start + m[1].length }
}

// Cargo.lock 里工作区成员各自的 version；不同步会让 `cargo build --locked` 直接拒绝构建。
const lockSpots = (text) => {
  const out = []
  const re = /\[\[package\]\]\nname = "(pi-carry-[^"]+)"\nversion = "([^"]*)"/g
  let m
  while ((m = re.exec(text)) !== null) {
    const start = m.index + m[0].length - m[2].length - 1
    out.push({ name: m[1], version: m[2], start, end: start + m[2].length })
  }
  return out
}

const gradleNameSpot = (text) => locate(text, /versionName\s*=?\s+"([^"]*)"/, `${GRADLE} versionName`)

const gradleCodeSpot = (text) => {
  const m = /versionCode\s*=\s*(\d+)/.exec(text)
  if (!m) throw new Error(`找不到版本锚点：${GRADLE} versionCode`)
  const start = m.index + m[0].indexOf(m[1])
  return { value: Number(m[1]), start, end: start + m[1].length }
}

const fail = (msg, code = 1) => {
  console.error(msg)
  process.exit(code)
}

const HELP = `版本单一来源同步脚本

用法:
  node scripts/set-version.mjs <x.y.z>          同步所有版本字段（Android versionCode 在版本变化时 +1）
  node scripts/set-version.mjs --check          以 Cargo.toml [workspace.package] 版本为基准校验其它文件
  node scripts/set-version.mjs --check <x.y.z>  校验，并额外要求基准等于该版本
  node scripts/set-version.mjs --help

退出码: 0 成功/一致，1 校验不一致或锚点缺失，2 版本号非法或用法错误`

const usage = () => {
  console.error(HELP)
  process.exit(2)
}

const argv = process.argv.slice(2)
if (argv.includes('--help') || argv.includes('-h')) {
  console.log(HELP)
  process.exit(0)
}
let check = false
let wanted
for (const arg of argv) {
  if (arg === '--check') {
    check = true
  } else if (arg.startsWith('-')) {
    usage()
  } else if (wanted === undefined) {
    wanted = arg
  } else {
    usage()
  }
}
if (wanted !== undefined && !SEMVER.test(wanted)) {
  fail(`版本号非法：${wanted}（要求 x.y.z，不接受 v 前缀）`, 2)
}

if (check) {
  const cargoText = read(CARGO)
  let base
  try {
    base = cargoSpot(cargoText).version
  } catch (e) {
    fail(e.message)
  }
  if (!SEMVER.test(base)) fail(`基准版本号非法：${CARGO} = ${base}`, 1)

  const mismatches = []
  if (wanted !== undefined && base !== wanted) mismatches.push(`${CARGO}: ${base} ≠ ${wanted}`)
  const others = [
    [TAURI, () => tauriSpot(read(TAURI))],
    [PS1, () => ps1Spot(read(PS1))],
    [GRADLE, () => gradleNameSpot(read(GRADLE))],
  ]
  for (const [path, spotOf] of others) {
    let spot
    try {
      spot = spotOf()
    } catch (e) {
      fail(e.message)
    }
    if (spot === null) {
      console.log(`${path}: 版本由 Cargo.toml 派生，无需比对`)
      continue
    }
    if (spot.version !== base) mismatches.push(`${path}: ${spot.version} ≠ ${base}`)
  }
  if (existsSync(resolve(root, LOCK))) {
    for (const spot of lockSpots(read(LOCK))) {
      if (spot.version !== base) mismatches.push(`${LOCK}: ${spot.name} ${spot.version} ≠ ${base}`)
    }
  }

  if (mismatches.length > 0) {
    console.error(`版本不一致（基准 ${CARGO} = ${base}）：`)
    for (const line of mismatches) console.error(`  ${line}`)
    process.exit(1)
  }
  console.log(`版本一致：${base}`)
  process.exit(0)
}

if (wanted === undefined) usage()
const next = wanted

// 单个文件：定位 -> 替换 -> 写回，内容没变就不写盘，保持 mtime 稳定。
const syncOne = (path, spotOf) => {
  const text = read(path)
  const spot = spotOf(text)
  if (spot === null) {
    console.log(`不变 ${path}（版本由 Cargo.toml 派生，无字面量可改）`)
    return
  }
  if (spot.version === next) {
    console.log(`不变 ${path}`)
    return
  }
  write(path, replaceAt(text, spot, next))
  console.log(`更新 ${path}: ${spot.version} -> ${next}`)
}

for (const [path, spotOf] of [
  [CARGO, cargoSpot],
  [TAURI, tauriSpot],
  [PS1, ps1Spot],
]) {
  try {
    syncOne(path, spotOf)
  } catch (e) {
    fail(e.message)
  }
}

// Cargo.lock 的成员版本必须同步，否则 --locked 构建会直接失败。
if (existsSync(resolve(root, LOCK))) {
  try {
    const text = read(LOCK)
    const stale = lockSpots(text).filter((spot) => spot.version !== next)
    if (stale.length === 0) {
      console.log(`不变 ${LOCK}`)
    } else {
      let out = text
      // 从后往前替换，避免前面的长度变化推歪后面的偏移。
      for (const spot of stale.sort((a, b) => b.start - a.start)) out = replaceAt(out, spot, next)
      write(LOCK, out)
      console.log(`更新 ${LOCK}: ${stale.length} 个工作区包 -> ${next}`)
    }
  } catch (e) {
    fail(e.message)
  }
}

// android/app/build.gradle.kts：versionName 跟着走；versionCode 只在版本号确实变化时 +1，
// 这样对同一版本重复执行不会把 versionCode 越推越高。
try {
  const text = read(GRADLE)
  const nameSpot = gradleNameSpot(text)
  const codeSpot = gradleCodeSpot(text)
  if (nameSpot.version === next) {
    console.log(`不变 ${GRADLE}`)
  } else {
    const codeNext = codeSpot.value + 1
    // 两段区间互不重叠；先替换靠后的那段，避免前面长度变化把后面的偏移推歪。
    const [first, firstVal, second, secondVal] =
      nameSpot.start > codeSpot.start
        ? [nameSpot, next, codeSpot, String(codeNext)]
        : [codeSpot, String(codeNext), nameSpot, next]
    write(GRADLE, replaceAt(replaceAt(text, first, firstVal), second, secondVal))
    console.log(`更新 ${GRADLE}: ${nameSpot.version} -> ${next}（versionCode ${codeSpot.value} -> ${codeNext}）`)
  }
} catch (e) {
  fail(e.message)
}
