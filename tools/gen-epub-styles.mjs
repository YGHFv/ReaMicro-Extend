import { readFileSync, writeFileSync, existsSync } from "node:fs";

const [, , sourcePath, outputPath, masksPath] = process.argv;
if (!sourcePath || !outputPath) {
  console.error("usage: node tools/gen-epub-styles.mjs <epubStyleLibrary.ts> <out.kt> [masks.json]");
  process.exit(1);
}
const source = readFileSync(sourcePath, "utf8");

const masks = masksPath && existsSync(masksPath) ? JSON.parse(readFileSync(masksPath, "utf8")) : {};

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

function readModules(constName) {
  const anchor = source.indexOf(`export const ${constName}: EpubStyleModule[] = [`);
  if (anchor < 0) throw new Error(`missing ${constName}`);

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

function sliceValueAt(text, start) {
  let index = start;
  while (index < text.length && /\s/.test(text[index])) index += 1;
  const head = text[index];
  let end;
  if (head === '"' || head === "'" || head === "`") {

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

function resolveConst(name) {
  const match = new RegExp(`const ${name}\\s*=\\s*`).exec(source);
  if (!match) return null;
  return evaluate(sliceValueAt(source, match.index + match[0].length));
}

function readField(entry, key) {
  const pattern = new RegExp(`(^|[\\s{,])${key}:\\s*`, "m");
  const match = pattern.exec(entry);
  if (!match) return null;
  const raw = sliceValueAt(entry, match.index + match[0].length);
  const value = evaluate(raw);
  if (value !== null) return value;
  return /^[A-Za-z_$][\w$]*$/.test(raw) ? resolveConst(raw) : null;
}

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

      skipped.push(`${group.constName}: ${id ?? entry.replace(/\s+/g, " ").slice(0, 60)}`);
      continue;
    }    styles.push({
      id,
      kind: group.kind,
      name,
      description: readField(entry, "description") ?? "",
      css: stripFontFamily(String(css)),

      markup: (readField(entry, "markup") ?? "").trim(),
    });
  }
}

const HEADER_TEMPLATE_CALL = /headerTemplateStyle\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*"([^"]*)"/g;
const headerEdgeCss = resolveConst("headerEdgeCss");
if (headerEdgeCss) {
  for (const match of source.matchAll(HEADER_TEMPLATE_CALL)) {
    const [, id, name, description] = match;
    if (styles.some((style) => style.id === id)) continue;
    styles.push({ id, kind: "Header", name, description, css: stripFontFamily(String(headerEdgeCss)) });
  }
}

for (const style of styles) {
  if (style.kind !== "Header") continue;
  const mask = masks[style.id];
  if (!mask) continue;
  style.maskAsset = `epub_header_mask/${style.id}.png`;
  style.sampleWidth = mask.width;
  style.sampleHeight = mask.height;
}

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
      markup: "",
    };
  });

const all = [...styles, ...volumeStyles];

function kotlinRawString(value) {

  return value.replace(/\$/g, "\${'$'}");
}

const entriesSource = all
  .map((style) => {
    const extras = [];
    if (style.maskAsset) extras.push(`            maskAsset = "${style.maskAsset}",`);
    if (style.sampleWidth) extras.push(`            sampleWidth = ${style.sampleWidth},`);
    if (style.sampleHeight) extras.push(`            sampleHeight = ${style.sampleHeight},`);
    if (style.markup) extras.push(`            markup = """${kotlinRawString(style.markup)}""",`);
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
