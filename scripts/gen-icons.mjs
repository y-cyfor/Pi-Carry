// 生成受控端托盘/窗口图标：深色圆角方块 + 紫色 π。
import { deflateSync } from 'node:zlib'
import { writeFileSync, mkdirSync } from 'node:fs'

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
const png = (size) => {
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
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(size, 0); ihdr.writeUInt32BE(size, 4)
  ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))
  ])
}
const ico = (pngBuf) => {
  const header = Buffer.alloc(6); header.writeUInt16LE(0, 0); header.writeUInt16LE(1, 2); header.writeUInt16LE(1, 4)
  const entry = Buffer.alloc(16)
  entry[0] = 32; entry[1] = 32; entry[2] = 0; entry[3] = 0
  entry.writeUInt16LE(1, 4); entry.writeUInt16LE(32, 6)
  entry.writeUInt32LE(pngBuf.length, 8); entry.writeUInt32LE(22, 12)
  return Buffer.concat([header, entry, pngBuf])
}
mkdirSync('crates/desktop/icons', { recursive: true })
for (const size of [32, 128, 256]) writeFileSync(`crates/desktop/icons/${size}x${size}.png`, png(size))
writeFileSync('crates/desktop/icons/icon.ico', ico(png(32)))
writeFileSync('crates/desktop/icons/icon.png', png(256))
console.log('icons generated')
