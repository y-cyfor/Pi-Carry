// scripts/gen-latest-json.test.mjs
//
// gen-latest-json.mjs 的端到端测试（node:test，无第三方依赖）。
// 每个用例都在系统临时目录里造夹具，真实启动被测 CLI，然后读 <dir>/latest.json
// 与 CLI 日志断言；绝不写仓库。
//
// 运行：
//   node --test scripts/gen-latest-json.test.mjs   （CI / 普通环境）
//   node scripts/gen-latest-json.test.mjs          （禁止 runner 子进程的沙箱里直跑）
//
// 沙箱注意：被测 CLI 用 spawnSync 启动，stdio 指向临时文件描述符而不是 pipe，
// 避免受限环境把管道当作命名管道而拒绝（EPERM）。
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { closeSync, existsSync, mkdtempSync, openSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { after, before, describe, it, test } from 'node:test'

const SCRIPT = join(dirname(fileURLToPath(import.meta.url)), 'gen-latest-json.mjs')

const VERSION = '0.3.1'
const BASE_URL = `https://example.test/download/v${VERSION}`
const PUB_DATE = '2026-01-02T03:04:05Z'
const NOTES = '第一行说明\n\n- 修复 A\n- 修复 B\n'

const WINDOWS_SETUP = `pi-carry-windows-10-x64-${VERSION}-setup.exe`
const WINDOWS_ZIP = `pi-carry-windows-10-x64-${VERSION}.zip`
const MACOS_APP = `pi-carry-macos-11-universal-${VERSION}.app.tar.gz`
const LINUX_X64_DEB = `pi-carry-linux-22.04-x64-${VERSION}.deb`
const LINUX_X64_RPM = `pi-carry-linux-22.04-x64-${VERSION}.rpm`
const LINUX_ARM64_DEB = `pi-carry-linux-24.04-arm64-${VERSION}.deb`
const LINUX_ARM64_RPM = `pi-carry-linux-24.04-arm64-${VERSION}.rpm`

// 排序后的期望平台键：Windows 两个（-nsis 主 + 基础回退）、macOS 两个基础键、
// Linux 两架构 × deb/rpm。不多不少，正好 8 个。
const EXPECTED_PLATFORM_KEYS = [
  'darwin-aarch64',
  'darwin-x86_64',
  'linux-aarch64-deb',
  'linux-aarch64-rpm',
  'linux-x86_64-deb',
  'linux-x86_64-rpm',
  'windows-x86_64',
  'windows-x86_64-nsis',
]

const ASSET_CONTENT = new Map([
  [WINDOWS_SETUP, 'MZ windows nsis setup installer\n'],
  [WINDOWS_ZIP, 'PK windows portable zip\n'],
  [MACOS_APP, 'gzip macos universal app bundle\n'],
  [LINUX_X64_DEB, 'debian package linux x64 22.04\n'],
  [LINUX_X64_RPM, 'rpm package linux x64 22.04\n'],
  [LINUX_ARM64_DEB, 'debian package linux arm64 24.04\n'],
  [LINUX_ARM64_RPM, 'rpm package linux arm64 24.04\n'],
])
const SIGNED_FILES = [
  WINDOWS_SETUP,
  MACOS_APP,
  LINUX_X64_DEB,
  LINUX_X64_RPM,
  LINUX_ARM64_DEB,
  LINUX_ARM64_RPM,
]

const signatureFor = (file) => `untrusted comment: signature for ${file}`

const tempDirs = []
const makeTempDir = () => {
  const dir = mkdtempSync(join(tmpdir(), 'pi-carry-latest-'))
  tempDirs.push(dir)
  return dir
}

after(() => {
  for (const dir of tempDirs.splice(0)) rmSync(dir, { recursive: true, force: true })
})

// 完整夹具：Windows setup+sig+zip、macOS app.tar.gz+sig、Linux x64 22.04 与
// arm64 24.04 的 deb/rpm+sig。Linux 发行版号故意不一致，证明按发现而非写死。
const buildFixture = () => {
  const dir = makeTempDir()
  for (const [name, content] of ASSET_CONTENT) writeFileSync(join(dir, name), content)
  for (const name of SIGNED_FILES) writeFileSync(join(dir, `${name}.sig`), signatureFor(name))
  return dir
}

const sha256Of = (path) => createHash('sha256').update(readFileSync(path)).digest('hex')

const readManifest = (dir) => JSON.parse(readFileSync(join(dir, 'latest.json'), 'utf8'))

const removeFixtureFile = (dir, name) => rmSync(join(dir, name))

// 真实执行被测 CLI。stdout/stderr 落到临时文件，避免 pipe 在受限环境里 EPERM。
const runCli = (dir, extraArgs = []) => {
  const logPath = join(makeTempDir(), 'cli.log')
  const fd = openSync(logPath, 'w')
  let result
  try {
    result = spawnSync(
      process.execPath,
      [
        SCRIPT,
        '--dir', dir,
        '--version', VERSION,
        '--base-url', BASE_URL,
        '--pub-date', PUB_DATE,
        ...extraArgs,
      ],
      { stdio: ['ignore', fd, fd] },
    )
  } finally {
    closeSync(fd)
  }
  const output = readFileSync(logPath, 'utf8')
  assert.equal(result.error, undefined, `启动 CLI 失败：${result.error?.message}\n输出：\n${output}`)
  assert.notEqual(result.status, null, `CLI 未正常结束（signal=${result.signal}）\n输出：\n${output}`)
  return { status: result.status, output }
}

describe('完整夹具（正向）', () => {
  let dir
  let run
  let manifest

  before(() => {
    dir = buildFixture()
    writeFileSync(join(dir, 'notes.md'), NOTES)
    run = runCli(dir, ['--notes-file', join(dir, 'notes.md')])
    assert.equal(run.status, 0, `期望退出码 0，实际 ${run.status}。输出：\n${run.output}`)
    manifest = readManifest(dir)
  })

  it('退出码 0，platforms 恰好 8 个键，顶层字段不变', () => {
    assert.deepStrictEqual(Object.keys(manifest.platforms).sort(), EXPECTED_PLATFORM_KEYS)
    assert.deepStrictEqual(Object.keys(manifest).sort(), [
      'notes',
      'platforms',
      'portable',
      'pub_date',
      'version',
    ])
  })

  it('Windows 两个 target 同 URL，指向 setup.exe，签名等于 .sig 内容', () => {
    const nsis = manifest.platforms['windows-x86_64-nsis']
    const fallback = manifest.platforms['windows-x86_64']
    assert.ok(nsis, '缺少 windows-x86_64-nsis')
    assert.ok(fallback, '缺少 windows-x86_64 回退键')
    assert.equal(nsis.url, `${BASE_URL}/${WINDOWS_SETUP}`)
    assert.equal(fallback.url, nsis.url, 'Windows 两个 target 必须指向同一个 setup.exe')
    assert.ok(nsis.url.endsWith(WINDOWS_SETUP))
    assert.equal(nsis.signature, signatureFor(WINDOWS_SETUP))
    assert.equal(fallback.signature, nsis.signature, 'Windows 两个 target 必须共用同一个 .sig')
    assert.equal(nsis.signature, readFileSync(join(dir, `${WINDOWS_SETUP}.sig`), 'utf8').trim())
  })

  it('macOS 两个基础键同 URL，指向同一个 .app.tar.gz', () => {
    const x64 = manifest.platforms['darwin-x86_64']
    const arm64 = manifest.platforms['darwin-aarch64']
    assert.ok(x64, '缺少 darwin-x86_64')
    assert.ok(arm64, '缺少 darwin-aarch64')
    assert.equal(x64.url, `${BASE_URL}/${MACOS_APP}`)
    assert.equal(arm64.url, x64.url, 'macOS 两个 target 必须指向同一个 Universal 更新包')
    assert.ok(x64.url.endsWith('.app.tar.gz'))
    assert.ok(!x64.url.endsWith('.dmg'))
    assert.equal(arm64.signature, x64.signature, 'macOS 两个 target 必须共用同一个 .sig')
  })

  it('4 个 Linux 键按发现结果指向 x64/arm64 的 deb/rpm（arm64 用 24.04）', () => {
    assert.ok(LINUX_ARM64_DEB.includes('24.04'), '夹具 arm64 应使用 24.04 发行版号')
    const expected = new Map([
      ['linux-x86_64-deb', LINUX_X64_DEB],
      ['linux-x86_64-rpm', LINUX_X64_RPM],
      ['linux-aarch64-deb', LINUX_ARM64_DEB],
      ['linux-aarch64-rpm', LINUX_ARM64_RPM],
    ])
    for (const [target, file] of expected) {
      const entry = manifest.platforms[target]
      assert.ok(entry, `缺少平台键 ${target}`)
      assert.equal(entry.url, `${BASE_URL}/${file}`, `${target} 的 URL 不对`)
      assert.equal(entry.signature, signatureFor(file), `${target} 的签名不对`)
    }
  })

  it('portable.windows-x86_64 的 url/sha256 正确（sha256 独立计算）', () => {
    assert.deepStrictEqual(manifest.portable, {
      'windows-x86_64': {
        version: VERSION,
        url: `${BASE_URL}/${WINDOWS_ZIP}`,
        sha256: sha256Of(join(dir, WINDOWS_ZIP)),
        notes: NOTES,
      },
    })
  })

  it('version / pub_date / notes 正确（notes 来自 --notes-file）', () => {
    assert.equal(manifest.version, VERSION)
    assert.equal(manifest.pub_date, PUB_DATE)
    assert.equal(manifest.notes, NOTES)
  })
})

test('缺少 macOS .sig：退出码 1，输出指出该 .sig 且不写出 latest.json', () => {
  const dir = buildFixture()
  removeFixtureFile(dir, `${MACOS_APP}.sig`)
  const { status, output } = runCli(dir)
  assert.equal(status, 1, `期望退出码 1，实际 ${status}。输出：\n${output}`)
  assert.ok(output.includes(`${MACOS_APP}.sig`), `输出未提到 ${MACOS_APP}.sig：\n${output}`)
  assert.ok(!existsSync(join(dir, 'latest.json')), '缺失资产时不应写出 latest.json')
})

test('同一架构出现两个发行版标签（x64 deb 22.04 + 24.04）：退出码 1，报「不唯一」', () => {
  const dir = buildFixture()
  const extra = `pi-carry-linux-24.04-x64-${VERSION}.deb`
  writeFileSync(join(dir, extra), 'debian package linux x64 24.04\n')
  const { status, output } = runCli(dir)
  assert.equal(status, 1, `期望退出码 1，实际 ${status}。输出：\n${output}`)
  assert.ok(
    output.includes('不唯一') || (output.includes(LINUX_X64_DEB) && output.includes(extra)),
    `输出未说明 Linux x64 deb 不唯一（应提到「不唯一」或两个文件名）：\n${output}`,
  )
})

test('--allow-missing 不能吞掉 Linux 歧义（x64 deb 22.04 + 24.04）：仍然退出码 1', () => {
  const dir = buildFixture()
  const extra = `pi-carry-linux-24.04-x64-${VERSION}.deb`
  writeFileSync(join(dir, extra), 'debian package linux x64 24.04\n')
  const { status, output } = runCli(dir, ['--allow-missing'])
  assert.equal(
    status,
    1,
    `--allow-missing 只允许跳过缺失，不能跳过歧义；期望退出码 1，实际 ${status}。输出：\n${output}`,
  )
  assert.ok(
    output.includes('不唯一') || (output.includes(LINUX_X64_DEB) && output.includes(extra)),
    `--allow-missing 下输出未说明 Linux x64 deb 不唯一：\n${output}`,
  )
  assert.ok(!existsSync(join(dir, 'latest.json')), '歧义时不应写出 latest.json')
})

test('缺少 Windows setup .sig：退出码 1，文件名去重后只出现一次', () => {
  const dir = buildFixture()
  const sigName = `${WINDOWS_SETUP}.sig`
  removeFixtureFile(dir, sigName)
  const { status, output } = runCli(dir)
  assert.equal(status, 1, `期望退出码 1，实际 ${status}。输出：\n${output}`)
  const occurrences = output.split(sigName).length - 1
  assert.equal(
    occurrences,
    1,
    `setup.exe.sig 只被一个物理文件使用，缺失应只报一次，实际出现 ${occurrences} 次：\n${output}`,
  )
})

test('缺少便携 zip：退出码 1，输出指出该 zip', () => {
  const dir = buildFixture()
  removeFixtureFile(dir, WINDOWS_ZIP)
  const { status, output } = runCli(dir)
  assert.equal(status, 1, `期望退出码 1，实际 ${status}。输出：\n${output}`)
  assert.ok(output.includes(WINDOWS_ZIP), `输出未提到缺失的便携包 ${WINDOWS_ZIP}：\n${output}`)
})

test('--allow-missing 且缺 macOS：退出码 0、有警告、platforms 不含 darwin 键但含 Windows/Linux', () => {
  const dir = buildFixture()
  removeFixtureFile(dir, MACOS_APP)
  removeFixtureFile(dir, `${MACOS_APP}.sig`)
  const { status, output } = runCli(dir, ['--allow-missing'])
  assert.equal(status, 0, `期望退出码 0，实际 ${status}。输出：\n${output}`)
  assert.ok(output.includes('警告：--allow-missing'), `缺少 --allow-missing 警告：\n${output}`)

  const manifest = readManifest(dir)
  const keys = Object.keys(manifest.platforms).sort()
  assert.deepStrictEqual(keys, EXPECTED_PLATFORM_KEYS.filter((key) => !key.startsWith('darwin-')))
  assert.deepStrictEqual(
    keys.filter((key) => key.startsWith('windows-')),
    ['windows-x86_64', 'windows-x86_64-nsis'],
  )
  for (const key of ['linux-x86_64-deb', 'linux-x86_64-rpm', 'linux-aarch64-deb', 'linux-aarch64-rpm']) {
    assert.ok(keys.includes(key), `--allow-missing 不应丢掉其它平台的键 ${key}`)
  }
})

test('--help：退出码 0，打印用法与退出码说明', () => {
  const dir = makeTempDir()
  const { status, output } = runCli(dir, ['--help'])
  assert.equal(status, 0, `期望退出码 0，实际 ${status}。输出：\n${output}`)
  assert.ok(output.includes('用法'), `--help 未打印用法：\n${output}`)
  assert.ok(output.includes('退出码'), `--help 未打印退出码说明：\n${output}`)
})
