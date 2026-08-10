// 从 TEpub-Editor 的头图样板图提取 alpha 通道，生成模块用的头图蒙版。
// 样板图本身有上百万像素的彩色内容，但套用时只用得到透明度，所以只保留 alpha 存成灰度 PNG，
// 体积从 ~17MB 降到 ~800KB。
// 用法: node tools/gen-header-masks.mjs <epub-style-library 资源目录> <输出目录>
import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { deflateSync, inflateSync } from "node:zlib";

const [, , sourceDir, outputDir] = process.argv;
if (!sourceDir || !outputDir) {
  console.error("usage: node tools/gen-header-masks.mjs <assets/epub-style-library> <out dir>");
  process.exit(1);
}

// 样式 id -> 样板图文件名。与 epubStyleLibrary.ts 里 headerTemplateSamples 的映射保持一致，
// template-* 优先于 sample-*（TEpub 的 templateDataUrl || sampleDataUrl）。
const MASKS = {
  "header-template-bottom-fade": "sample-character-gallery.png",
  "header-template-torn-edge": "sample-sword-duel.png",
  "header-template-scatter-edge": "sample-harbor-studio.png",
  "header-template-ink-edge": "sample-night-guard.png",
  "header-template-diagonal-brush": "sample-court-lineup.png",
  "header-template-right-memory-collage": "sample-right-memory-collage.png",
  "header-template-delivery-bike-collage": "template-delivery-bike-collage.png",
  "header-template-cloud-gate-ink-banner": "template-cloud-gate-ink-banner.png",
};

function crc32(buf) {
  let c;
  const table = crc32.table || (crc32.table = (() => {
    const t = new Int32Array(256);
    for (let n = 0; n < 256; n += 1) {
      c = n;
      for (let k = 0; k < 8; k += 1) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      t[n] = c;
    }
    return t;
  })());
  let crc = -1;
  for (let i = 0; i < buf.length; i += 1) crc = (crc >>> 8) ^ table[(crc ^ buf[i]) & 0xff];
  return (crc ^ -1) >>> 0;
}

function chunk(type, data) {
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, "ascii"), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([length, body, crc]);
}

/** 解析 PNG 到 {width,height,alpha:Uint8Array}，只支持 8-bit RGBA/灰度+alpha 非隔行图。 */
function readPngAlpha(file) {
  const buf = readFileSync(file);
  let offset = 8;
  let width = 0;
  let height = 0;
  let colorType = 0;
  let bitDepth = 0;
  const idat = [];
  while (offset < buf.length) {
    const length = buf.readUInt32BE(offset);
    const type = buf.toString("ascii", offset + 4, offset + 8);
    const data = buf.subarray(offset + 8, offset + 8 + length);
    if (type === "IHDR") {
      width = data.readUInt32BE(0);
      height = data.readUInt32BE(4);
      bitDepth = data[8];
      colorType = data[9];
      if (data[12] !== 0) throw new Error(`${file}: interlaced PNG unsupported`);
    } else if (type === "IDAT") {
      idat.push(Buffer.from(data));
    } else if (type === "IEND") {
      break;
    }
    offset += 12 + length;
  }
  if (bitDepth !== 8) throw new Error(`${file}: bit depth ${bitDepth} unsupported`);
  const channels = colorType === 6 ? 4 : colorType === 4 ? 2 : colorType === 2 ? 3 : 1;
  if (channels !== 4 && channels !== 2) return { width, height, alpha: null };
  const raw = inflateSync(Buffer.concat(idat));
  const stride = width * channels;
  const out = Buffer.alloc(width * height);
  const line = Buffer.alloc(stride);
  const prev = Buffer.alloc(stride);
  let pos = 0;
  for (let y = 0; y < height; y += 1) {
    const filter = raw[pos];
    pos += 1;
    raw.copy(line, 0, pos, pos + stride);
    pos += stride;
    // PNG 逐行滤波还原
    for (let x = 0; x < stride; x += 1) {
      const a = x >= channels ? line[x - channels] : 0;
      const b = prev[x];
      const c = x >= channels ? prev[x - channels] : 0;
      switch (filter) {
        case 1: line[x] = (line[x] + a) & 0xff; break;
        case 2: line[x] = (line[x] + b) & 0xff; break;
        case 3: line[x] = (line[x] + ((a + b) >> 1)) & 0xff; break;
        case 4: {
          const p = a + b - c;
          const pa = Math.abs(p - a);
          const pb = Math.abs(p - b);
          const pc = Math.abs(p - c);
          const pred = pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
          line[x] = (line[x] + pred) & 0xff;
          break;
        }
        default: break;
      }
    }
    for (let x = 0; x < width; x += 1) out[y * width + x] = line[x * channels + channels - 1];
    line.copy(prev);
  }
  return { width, height, alpha: out };
}

/** 把灰度数据写成 8-bit greyscale PNG。 */
function writeGreyPng(target, width, height, grey) {
  const raw = Buffer.alloc((width + 1) * height);
  for (let y = 0; y < height; y += 1) {
    raw[y * (width + 1)] = 0;
    grey.copy(raw, y * (width + 1) + 1, y * width, (y + 1) * width);
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;
  ihdr[9] = 0;
  const png = Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk("IHDR", ihdr),
    chunk("IDAT", deflateSync(raw, { level: 9 })),
    chunk("IEND", Buffer.alloc(0)),
  ]);
  writeFileSync(target, png);
  return png.length;
}

if (!existsSync(outputDir)) mkdirSync(outputDir, { recursive: true });
const known = new Set(readdirSync(sourceDir));
let total = 0;
const sizes = {};
for (const [styleId, fileName] of Object.entries(MASKS)) {
  if (!known.has(fileName)) {
    console.warn(`skip ${styleId}: ${fileName} not found`);
    continue;
  }
  const { width, height, alpha } = readPngAlpha(join(sourceDir, fileName));
  if (!alpha) {
    console.warn(`skip ${styleId}: ${fileName} has no alpha channel`);
    continue;
  }
  let transparent = false;
  for (let i = 0; i < alpha.length; i += 1) {
    if (alpha[i] < 250) { transparent = true; break; }
  }
  if (!transparent) {
    console.warn(`skip ${styleId}: mask is fully opaque`);
    continue;
  }
  const bytes = writeGreyPng(join(outputDir, `${styleId}.png`), width, height, alpha);
  sizes[styleId] = { width, height, bytes };
  total += bytes;
  console.log(`${styleId}: ${width}x${height} -> ${Math.round(bytes / 1024)}KB`);
}
console.log(`total ${Math.round(total / 1024)}KB -> ${outputDir}`);
writeFileSync(join(outputDir, "masks.json"), JSON.stringify(sizes, null, 2), "utf8");
