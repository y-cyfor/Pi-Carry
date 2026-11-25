// 生成受控端托盘/窗口图标：深色圆角方块 + 紫色 π。
// 纯 Node 实现（只用 node:zlib / node:fs），一次产出 Tauri 各平台与 macOS template 所需的全部图标。
import { deflateSync } from 'node:zlib'
import { writeFileSync, mkdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

// 相对脚本自身定位，保证从任意工作目录执行都能写回仓库内同一目录。
const OUT_DIR = fileURLToPath(new URL('../crates/desktop/icons/', import.meta.url))

const crcTable = new Uint32Array(256)
for (let n = 0; n < 256; n++) {
  let c = n
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
  crcTable[n] = c >>> 0
}
const crc32 = (buf) => {
  let c = 0xffffffff
  for (const b of buf) c = crcTable[(c ^ b) & 0xff] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}
const chunk = (type, data) => {
  const t = Buffer.from(type, 'ascii')
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length)
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(Buffer.concat([t, data])))
  return Buffer.concat([len, t, data, crc])
}

// 位图编码为 PNG。raw 是按行排列的 [过滤字节 0, R, G, B, A] × size 缓冲。
const encodePng = (size, raw) => {
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(size, 0); ihdr.writeUInt32BE(size, 4)
  ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))
  ])
}

// 应用图标位图：近黑紫径向渐变圆角方块 + 奶白 π 字形。
const appPixels = (size) => {
  const raw = Buffer.alloc((size * 4 + 1) * size)
  const cx = size / 2, cy = size / 2, r = size * 0.46
  for (let y = 0; y < size; y++) {
    raw[y * (size * 4 + 1)] = 0
    for (let x = 0; x < size; x++) {
      const o = y * (size * 4 + 1) + 1 + x * 4
      const d = Math.hypot(x + 0.5 - cx, y + 0.5 - cy)
      if (d > r) { raw[o + 3] = 0; continue }
      // 背景：近黑紫径向渐变
      const t = d / r
      raw[o] = Math.round(12 + 26 * (1 - t))
      raw[o + 1] = Math.round(10 + 14 * (1 - t))
      raw[o + 2] = Math.round(26 + 48 * (1 - t))
      raw[o + 3] = 255
      // π 字形：两竖 + 横杠 + 右弯钩
      const u = (x + 0.5) / size, v = (y + 0.5) / size
      const bar = v > 0.33 && v < 0.40 && u > 0.28 && u < 0.74
      const left = u > 0.33 && u < 0.405 && v > 0.36 && v < 0.71
      const right = u > 0.585 && u < 0.66 && v > 0.36 && v < 0.64
      const hook = v > 0.62 && v < 0.71 && u > 0.585 && u < 0.735
      if (bar || left || right || hook) {
        raw[o] = 245; raw[o + 1] = 243; raw[o + 2] = 255
      }
    }
  }
  return raw
}

// 托盘 template 位图：无背景方块，只有纯黑 RGB(0,0,0) + alpha 的 π 字形。
// 设计栅格固定为 22 单位，笔画恒为 2 单位，因此 22px 下线条正好 2px、44px 下正好 4px。
const TRAY_GRID = 22
const TRAY_STROKE = 2
// 矩形按 [x0, y0, x1, y1) 书写：横杠、左竖、右竖、右弯钩。
// 两竖都探到同一条基线（y=19），右竖底部向右加宽成弯钩，与应用图标里的钩形一致。
const TRAY_PI_RECTS = [
  [3, 3, 19, 3 + TRAY_STROKE],              // 横杠
  [5, 5, 5 + TRAY_STROKE, 19],              // 左竖
  [14, 5, 14 + TRAY_STROKE, 19],            // 右竖
  [14 + TRAY_STROKE, 19 - TRAY_STROKE, 19, 19] // 右弯钩（向右伸出）
]
const trayPixels = (size) => {
  const raw = Buffer.alloc((size * 4 + 1) * size)
  const unit = size / TRAY_GRID
  for (let y = 0; y < size; y++) {
    raw[y * (size * 4 + 1)] = 0
    for (let x = 0; x < size; x++) {
      const o = y * (size * 4 + 1) + 1 + x * 4
      // 用像素中心在 22 单位栅格里的坐标判定覆盖，44px 时正好是 22px 的 2 倍整数放大
      const u = (x + 0.5) / unit, v = (y + 0.5) / unit
      const inside = TRAY_PI_RECTS.some(([x0, y0, x1, y1]) => u >= x0 && u < x1 && v >= y0 && v < y1)
      if (!inside) { raw[o + 3] = 0; continue }
      raw[o] = 0; raw[o + 1] = 0; raw[o + 2] = 0
      raw[o + 3] = 255
    }
  }
  return raw
}

const appIcon = (size) => encodePng(size, appPixels(size))
const trayIcon = (size) => encodePng(size, trayPixels(size))

// 多尺寸 ICO：头部 6 字节 + 每条目 16 字节目录项，图像数据（PNG）按目录顺序紧随其后。
const ico = (entries) => {
  const header = Buffer.alloc(6)
  header.writeUInt16LE(0, 0)                 // reserved
  header.writeUInt16LE(1, 2)                 // type = 1（图标）
  header.writeUInt16LE(entries.length, 4)    // 条目数
  const dir = Buffer.alloc(16 * entries.length)
  let offset = 6 + dir.length                // 图像数据起始偏移
  entries.forEach(({ size, data }, i) => {
    const o = i * 16
    // 宽/高字节：0 表示 256
    dir[o] = size >= 256 ? 0 : size
    dir[o + 1] = size >= 256 ? 0 : size
    dir[o + 2] = 0                           // 调色板颜色数（真彩为 0）
    dir[o + 3] = 0                           // reserved
    dir.writeUInt16LE(1, o + 4)              // color planes
    dir.writeUInt16LE(32, o + 6)             // bits per pixel
    dir.writeUInt32LE(data.length, o + 8)    // 图像数据长度
    dir.writeUInt32LE(offset, o + 12)        // 图像数据偏移
    offset += data.length
  })
  return Buffer.concat([header, dir, ...entries.map((e) => e.data)])
}

// ICNS 容器：'icns' + 大端总长度，随后每块为 4 字节类型 + 4 字节大端块长度（含这 8 字节）+ PNG 数据。
const icns = (entries) => {
  const blocks = entries.map(({ type, data }) => {
    const head = Buffer.alloc(8)
    head.write(type, 0, 'ascii')
    head.writeUInt32BE(data.length + 8, 4)
    return Buffer.concat([head, data])
  })
  const body = Buffer.concat(blocks)
  const head = Buffer.alloc(8)
  head.write('icns', 0, 'ascii')
  head.writeUInt32BE(body.length + 8, 4)     // 总长度同样含头部 8 字节
  return Buffer.concat([head, body])
}

mkdirSync(OUT_DIR, { recursive: true })

const png256 = appIcon(256)
const files = [
  ['32x32.png', appIcon(32)],
  ['128x128.png', appIcon(128)],
  ['256x256.png', png256],
  ['512x512.png', appIcon(512)],
  ['128x128@2x.png', png256],               // 实际 256×256 像素，内容与 256x256.png 一致
  ['icon.png', png256],                     // 兼容旧路径，保持与之前一致
  ['icon.ico', ico([16, 32, 48, 256].map((size) => ({ size, data: appIcon(size) })))],
  ['icon.icns', icns([
    [16, 'icp4'], [32, 'icp5'], [128, 'ic07'], [256, 'ic08'], [512, 'ic09']
  ].map(([size, type]) => ({ type, data: appIcon(size) })))],
  ['tray-template.png', trayIcon(22)],
  ['tray-template@2x.png', trayIcon(44)]
]

for (const [name, data] of files) {
  writeFileSync(`${OUT_DIR}/${name}`, data)
  console.log(`${OUT_DIR}/${name}  ${data.length} bytes`)
}
console.log(`icons generated: ${files.length} files`)
