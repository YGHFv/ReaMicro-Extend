// 从 TEpub-Editor 的 epubStyleLibrary.ts 抽取内置样式，生成模块用的 Kotlin 样式库。
// 用法: node tools/gen-epub-styles.mjs <epubStyleLibrary.ts 路径> <输出 Kotlin 路径> [masks.json 路径]
import { readFileSync, writeFileSync, existsSync } from "node:fs";

const [, , sourcePath, outputPath, masksPath] = process.argv;
if (!sourcePath || !outputPath) {
  console.error("usage: node tools/gen-epub-styles.mjs <epubStyleLibrary.ts> <out.kt> [masks.json]");
  process.exit(1);
}
const source = readFileSync(sourcePath, "utf8");
// gen-header-masks.mjs 产出的蒙版清单：样式 id -> {width, height}
const masks = masksPath && existsSync(masksPath) ? JSON.parse(readFileSync(masksPath, "utf8")) : {};

/** 从 startIndex 处的 '[' 开始，返回与之配对的 ']' 下标。 */
function matchBracket(text, startIndex, open, close) {
  let depth = 0;
  for (let i = startIndex; i < text.length; i += 1) {
    const ch = text[i];
    if (ch === "\\") { i += 1; continue; }
    if (ch === '"' || ch === "'" || ch === "`") {
      const quote = ch;
      i += 1;
      while (i < text.length && text[i] !== quote) {
        if (text[i] === "\\") i += 1;
        i += 1;
      }
      continue;
    }
    if (ch === open) depth += 1;
    else if (ch === close) {
      depth -= 1;
      if (depth === 0) return i;
    }
  }
  throw new Error(`unbalanced ${open}${close} from ${startIndex}`);
}

/** 取出 `export const NAME: EpubStyleModule[] = [ ... ];` 数组体内的每个对象字面量。 */
function readModules(constName) {
  const anchor = source.indexOf(`export const ${constName}: EpubStyleModule[] = [`);
  if (anchor < 0) throw new Error(`missing ${constName}`);
  // 注意声明行里 `EpubStyleModule[]` 也含 '['，必须从 `= [` 处定位数组体。
  const arrayStart = source.indexOf("= [", anchor) + 2;
  const arrayEnd = matchBracket(source, arrayStart, "[", "]");
  const body = source.slice(arrayStart + 1, arrayEnd);
  const entries = [];
  for (let i = 0; i < body.length; i += 1) {
    if (body[i] !== "{") continue;
    const end = matchBracket(body, i, "{", "}");
    entries.push(body.slice(i, end + 1));
    i = end;
  }
  return entries;
}

/** 从 [start] 处读出一个字面量（字符串/模板串/数组）的源码片段。 */
function sliceValueAt(text, start) {
  let index = start;
  while (index < text.length && /\s/.test(text[index])) index += 1;
  const head = text[index];
  let end;
  if (head === '"' || head === "'" || head === "`") {
    // 字符串字面量：扫到配对的未转义引号，CSS 模板里的缩进和冒号不会误伤。
    end = index + 1;
    while (end < text.length && text[end] !== head) {
      if (text[end] === "\\") end += 1;
      end += 1;
    }
    end += 1;
  } else if (head === "[") {
    end = matchBracket(text, index, "[", "]") + 1;
  } else {
    const comma = text.indexOf(",", index);
    end = comma < 0 ? text.length : comma;
  }
  return text.slice(index, end).trim();
}

function evaluate(raw) {
  if (!raw) return null;
  try {
    return Function(`"use strict"; return (${raw});`)();
  } catch {
    return null;
  }
}

/** 解析 `const NAME = <字面量>` 形式的模块级常量，供 `css: headerEdgeCss` 这类引用取值。 */
function resolveConst(name) {
  const match = new RegExp(`const ${name}\\s*=\\s*`).exec(source);
  if (!match) return null;
  return evaluate(sliceValueAt(source, match.index + match[0].length));
}

/** 求值对象字面量里某个键的值表达式；键不存在返回 null。 */
function readField(entry, key) {
  const pattern = new RegExp(`(^|[\\s{,])${key}:\\s*`, "m");
  const match = pattern.exec(entry);
  if (!match) return null;
  const raw = sliceValueAt(entry, match.index + match[0].length);
  const value = evaluate(raw);
  if (value !== null) return value;
  return /^[A-Za-z_$][\w$]*$/.test(raw) ? resolveConst(raw) : null;
}

// 内置样式 CSS 里的 font-family 一律清洗掉：字体由样式面板的字体选择单独控制，
// CSS 里再写一份只会互相打架，而且样板里的 "zdy1"/"llf" 这类字体名在设备上根本不存在。
function stripFontFamily(css) {
  return css
    .split("\n")
    .filter((line) => !/^\s*font-family\s*:/i.test(line))
    .join("\n")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}

const GROUPS = [
  { constName: "EPUB_HEADER_STYLES", kind: "Header" },
  { constName: "EPUB_TITLE_STYLES", kind: "Title" },
  { constName: "EPUB_ILLUSTRATION_STYLES", kind: "Illustration" },
  { constName: "EPUB_TRANSITION_STYLES", kind: "Transition" },
];

const styles = [];
const skipped = [];
for (const group of GROUPS) {
  for (const entry of readModules(group.constName)) {
    const id = readField(entry, "id");
    const name = readField(entry, "name");
    const css = readField(entry, "css");
    if (!id || !name || !css) {
      // 样板图工厂产物（headerTemplateStyle / spread）只有 base64 样板图差异、CSS 相同，跳过。
      skipped.push(`${group.constName}: ${id ?? entry.replace(/\s+/g, " ").slice(0, 60)}`);
      continue;
    }    styles.push({
      id,
      kind: group.kind,
      name,
      description: readField(entry, "description") ?? "",
      css: stripFontFamily(String(css)),
    });
  }
}

// 样板图头图在 TS 里是 headerTemplateStyle(...) 工厂调用（或其 spread），对象字面量解析拿不到。
// 它们的 CSS 与贴边头图相同，真正的差异是各自的透明样板图 —— 也就是模块里的头图蒙版。
const HEADER_TEMPLATE_CALL = /headerTemplateStyle\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*"([^"]*)"/g;
const headerEdgeCss = resolveConst("headerEdgeCss");
if (headerEdgeCss) {
  for (const match of source.matchAll(HEADER_TEMPLATE_CALL)) {
    const [, id, name, description] = match;
    if (styles.some((style) => style.id === id)) continue;
    styles.push({ id, kind: "Header", name, description, css: stripFontFamily(String(headerEdgeCss)) });
  }
}

// 给头图样式补上蒙版与样板尺寸；没有蒙版的头图样式按原图直出。
for (const style of styles) {
  if (style.kind !== "Header") continue;
  const mask = masks[style.id];
  if (!mask) continue;
  style.maskAsset = `epub_header_mask/${style.id}.png`;
  style.sampleWidth = mask.width;
  style.sampleHeight = mask.height;
}

// 卷标样式由章节标题样式派生：选择器整体换成卷首页接口，并放大字号、拉开上边距。
const CHAPTER_TO_VOLUME = [
  [/\.te-chapter-title/g, ".te-volume-title"],
  [/\.te-chapter-number/g, ".te-volume-number"],
  [/\.te-chapter-name/g, ".te-volume-name"],
];
const volumeStyles = styles
  .filter((style) => style.kind === "Title")
  .map((style) => {
    let css = style.css;
    CHAPTER_TO_VOLUME.forEach(([from, to]) => { css = css.replace(from, to); });
    return {
      id: style.id.replace(/^title-/, "volume-"),
      kind: "Volume",
      name: style.name.replace(/章题$/, "卷题").replace(/章$/, "卷"),
      description: style.description.replace(/章节/g, "卷"),
      css,
    };
  });

const all = [...styles, ...volumeStyles];

function kotlinRawString(value) {
  // Kotlin 原始字符串里 $ 需要转义，其余原样保留。
  return value.replace(/\$/g, "\${'$'}");
}

const entriesSource = all
  .map((style) => {
    const extras = [];
    if (style.maskAsset) extras.push(`            maskAsset = "${style.maskAsset}",`);
    if (style.sampleWidth) extras.push(`            sampleWidth = ${style.sampleWidth},`);
    if (style.sampleHeight) extras.push(`            sampleHeight = ${style.sampleHeight},`);
    return `        OnlineEpubStyle(
            id = "${style.id}",
            kind = OnlineEpubStyleKind.${style.kind},
            name = "${style.name.replace(/"/g, '\\"')}",
            description = "${style.description.replace(/"/g, '\\"').replace(/\s+/g, " ").trim()}",
            builtIn = true,
${extras.length ? `${extras.join("\n")}\n` : ""}            css = """${kotlinRawString(style.css)}""",
        ),`;
  })
  .join("\n");

const output = `package com.reamicro.fix.settings

/**
 * 在线补全成书样式的内置样式库。
 *
 * 内容移植自 TEpub-Editor 的 epubStyleLibrary.ts（由 tools/gen-epub-styles.mjs 生成，请勿手改），
 * 卷标样式由章节标题样式派生：选择器换成卷首页接口。
 */
internal object OnlineEpubStyleLibrary {
    val BUILT_INS: List<OnlineEpubStyle> = listOf(
${entriesSource}
    )

    fun byKind(kind: OnlineEpubStyleKind): List<OnlineEpubStyle> = BUILT_INS.filter { it.kind == kind }

    fun byId(id: String): OnlineEpubStyle? = BUILT_INS.firstOrNull { it.id == id }
}
`;

writeFileSync(outputPath, output, "utf8");
console.log(`generated ${all.length} styles -> ${outputPath}`);
for (const kind of ["Header", "Title", "Illustration", "Transition", "Volume"]) {
  console.log(`  ${kind}: ${all.filter((s) => s.kind === kind).length}`);
}
if (skipped.length) console.log(`skipped ${skipped.length}:\n  ${skipped.join("\n  ")}`);
