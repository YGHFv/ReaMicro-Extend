package com.reamicro.fix.settings

/**
 * 在线补全成书样式的内置样式库。
 *
 * 内容移植自 TEpub-Editor 的 epubStyleLibrary.ts（由 tools/gen-epub-styles.mjs 生成，请勿手改），
 * 卷标样式由章节标题样式派生：选择器换成卷首页接口。
 */
internal object OnlineEpubStyleLibrary {
    val BUILT_INS: List<OnlineEpubStyle> = listOf(
        OnlineEpubStyle(
            id = "header-standard-edge",
            kind = OnlineEpubStyleKind.Header,
            name = "贴边头图",
            description = "图片贴住页面上、左、右边缘，标题和正文从下方开始。",
            builtIn = true,
            css = """.te-header-figure {
  margin: 0 -1.5em 1.6em;
  padding: 0;
  position: relative;
  overflow: hidden;
  line-height: 0;
  text-align: center;
  text-indent: 0;
  duokan-text-indent: 0;
  duokan-bleed: lefttopright;
}

.te-header-image {
  display: block;
  width: 100%;
  max-width: none;
  height: auto;
  object-fit: cover;
}

.te-header-caption {
  display: none;
}""",
        ),
        OnlineEpubStyle(
            id = "header-template-right-memory-collage",
            kind = OnlineEpubStyleKind.Header,
            name = "右侧散边留白头图",
            description = "主体集中在右侧，左侧保留大片留白，并带碎片、颗粒和旧照散边效果。",
            builtIn = true,
            maskAsset = "epub_header_mask/header-template-right-memory-collage.png",
            sampleWidth = 1080,
            sampleHeight = 664,
            css = """.te-header-figure {
  margin: 0 -1.5em 1.65em;
  padding: 0;
  position: relative;
  overflow: hidden;
  line-height: 0;
  text-align: center;
  text-indent: 0;
  duokan-text-indent: 0;
  duokan-bleed: lefttopright;
}

.te-header-image {
  display: block;
  width: 100%;
  max-width: none;
  height: auto;
  object-fit: cover;
}

.te-header-caption {
  display: none;
}""",
        ),
        OnlineEpubStyle(
            id = "header-card-shadow",
            kind = OnlineEpubStyleKind.Header,
            name = "卡片头图",
            description = "保留留白和轻阴影，适合不希望图片铺满页面的章节。",
            builtIn = true,
            css = """.te-header-figure {
  margin: 1.2em auto 1.8em;
  padding: 0.35em;
  width: 92%;
  border: 1px solid #d9c7a2;
  background: #fffaf0;
  box-shadow: 0 0.45em 1.4em rgba(88, 64, 34, 0.18);
  box-sizing: border-box;
}

.te-header-image {
  display: block;
  width: 100%;
  height: auto;
  object-fit: cover;
}

.te-header-caption {
  display: none;
}""",
        ),
        OnlineEpubStyle(
            id = "header-dark-vignette-edge",
            kind = OnlineEpubStyleKind.Header,
            name = "暗角遮罩贴边头图",
            description = "贴边显示并在头图底部增加暗色压底，标题仍按所选标题样式单独渲染。",
            builtIn = true,
            css = """.te-header-figure {
  margin: 0 -1.5em 1.2em;
  min-height: 14em;
  position: relative;
  overflow: hidden;
  line-height: 0;
  text-align: center;
  text-indent: 0;
  duokan-text-indent: 0;
  duokan-bleed: lefttopright;
  background: #172033;
}

.te-header-image {
  display: block;
  width: 100%;
  max-width: none;
  height: 14em;
  object-fit: cover;
  opacity: 0.9;
}

.te-header-figure::after {
  content: "";
  position: absolute;
  inset: 0;
  background: linear-gradient(to bottom, rgba(23,32,51,0.08), rgba(23,32,51,0.72));
}

.te-header-caption {
  display: none;
}""",
        ),
        OnlineEpubStyle(
            id = "header-fine-frame-edge",
            kind = OnlineEpubStyleKind.Header,
            name = "细线装帧头图",
            description = "图片贴近页顶，底部以细线和留白收束，适合文艺、悬疑、现代题材。",
            builtIn = true,
            css = """.te-header-figure {
  margin: 0 -1.2em 1.9em;
  padding: 0 0 0.42em;
  position: relative;
  overflow: hidden;
  line-height: 0;
  text-align: center;
  text-indent: 0;
  duokan-text-indent: 0;
  border-bottom: 1px solid #243447;
  background: #f8fafc;
}

.te-header-figure::after {
  content: "";
  position: absolute;
  left: 16%;
  right: 16%;
  bottom: 0.18em;
  border-bottom: 1px solid #c8a65a;
}

.te-header-image {
  display: block;
  width: 100%;
  max-width: none;
  height: auto;
  object-fit: cover;
}

.te-header-caption {
  display: none;
}""",
        ),
        OnlineEpubStyle(
            id = "header-floating-print",
            kind = OnlineEpubStyleKind.Header,
            name = "浮印留白头图",
            description = "保留四周留白和轻微投影，像插页版画，适合古风、奇幻和人物向章节。",
            builtIn = true,
            css = """.te-header-figure {
  margin: 1.1em auto 2em;
  padding: 0.28em;
  width: 90%;
  box-sizing: border-box;
  border: 1px solid #d7c7a7;
  background: #fffdf7;
  box-shadow: 0 0.35em 1em rgba(34, 25, 18, 0.14);
  line-height: 0;
  text-align: center;
  text-indent: 0;
  duokan-text-indent: 0;
}

.te-header-image {
  display: block;
  width: 100%;
  height: auto;
  object-fit: cover;
}

.te-header-caption {
  display: none;
}""",
        ),
        OnlineEpubStyle(
            id = "header-cinematic-crop",
            kind = OnlineEpubStyleKind.Header,
            name = "电影裁幅头图",
            description = "固定横幅高度，顶部贴边并加深色压边，适合动作、悬疑、科幻章节。",
            builtIn = true,
            css = """.te-header-figure {
  margin: 0 -1.5em 1.45em;
  height: 13.2em;
  position: relative;
  overflow: hidden;
  line-height: 0;
  text-align: center;
  text-indent: 0;
  duokan-text-indent: 0;
  background: #0f172a;
}

.te-header-image {
  display: block;
  width: 100%;
  max-width: none;
  height: 13.2em;
  object-fit: cover;
}

.te-header-figure::before,
.te-header-figure::after {
  content: "";
  position: absolute;
  left: 0;
  right: 0;
  height: 1.1em;
  background: rgba(15, 23, 42, 0.82);
}

.te-header-figure::before {
  top: 0;
}

.te-header-figure::after {
  bottom: 0;
}

.te-header-caption {
  display: none;
}""",
        ),
        OnlineEpubStyle(
            id = "title-classic-red",
            kind = OnlineEpubStyleKind.Title,
            name = "经典红章",
            description = "居中双行标题，章节序号偏暗，章节名使用红色强调。",
            builtIn = true,
            css = """.te-chapter-title {
  text-align: center;
  margin: 2em 0 3em;
  font-size: 1.2em;
  font-weight: 900;
  color: #c2181e;
}

.te-chapter-number {
  display: block;
  color: #413245;
  font-size: 0.82em;
  line-height: 1.35;
}

.te-chapter-name {
  display: block;
  color: #c2181e;
}""",
        ),
        OnlineEpubStyle(
            id = "title-ink-line",
            kind = OnlineEpubStyleKind.Title,
            name = "墨线章题",
            description = "黑白细线分隔，适合正文密集、低装饰的作品。",
            builtIn = true,
            css = """.te-chapter-title {
  margin: 2.4em auto 2.8em;
  padding: 0.9em 0;
  width: 82%;
  border-top: 1px solid #1f2937;
  border-bottom: 1px solid #1f2937;
  color: #111827;
  text-align: center;
  text-indent: 0;
}

.te-chapter-number {
  display: block;
  margin-bottom: 0.45em;
  color: #6b7280;
  font-size: 0.78em;
}

.te-chapter-name {
  display: block;
  font-size: 1.18em;
  font-weight: 700;
}""",
        ),
        OnlineEpubStyle(
            id = "title-purple-red-emphasis",
            kind = OnlineEpubStyleKind.Title,
            name = "暗紫红重点章题",
            description = "居中双行标题，章节序号用暗紫色，章节名用红色强调。",
            builtIn = true,
            css = """.te-chapter-title {
  text-align: center;
  font-weight: 900;
  font-size: 0.8em;
  margin: 1em 0 3em;
  color: #413245;
  line-height: 1.3;
  text-indent: 0;
  duokan-text-indent: 0;
}

.te-chapter-number {
  display: block;
  color: #413245;
}

.te-chapter-name {
  display: block;
  font-size: 1.2em;
  font-weight: 900;
  color: #c2181e;
}""",
        ),
        OnlineEpubStyle(
            id = "title-teal-left-heading",
            kind = OnlineEpubStyleKind.Title,
            name = "青蓝左齐章题",
            description = "左对齐双行标题，章节序号为黑色小字，章节名为青蓝色。",
            builtIn = true,
            css = """.te-chapter-title {
  font-size: 1.1em;
  line-height: 1.2;
  font-weight: bold;
  text-align: left;
  margin: 1em 0;
  padding-bottom: 1em;
  text-indent: 0;
  duokan-text-indent: 0;
  color: #02586d;
}

.te-chapter-number {
  display: block;
  margin-bottom: 0.35em;
  font-size: 0.8em;
  color: #000;
  font-weight: bold;
}

.te-chapter-name {
  display: block;
  color: #02586d;
  font-weight: bold;
}""",
        ),
        OnlineEpubStyle(
            id = "title-vermilion-center",
            kind = OnlineEpubStyleKind.Title,
            name = "朱红居中章题",
            description = "居中双行标题，序号较小，标题名使用朱红色强调。",
            builtIn = true,
            css = """.te-chapter-title {
  margin-top: 0;
  margin-bottom: 1.5em;
  color: #ab1d22;
  font-size: 1.2em;
  line-height: 1.3;
  text-align: center;
  text-indent: 0;
  duokan-text-indent: 0;
}

.te-chapter-number {
  display: block;
  font-size: 0.7em;
  color: #ab1d22;
  font-weight: 500;
}

.te-chapter-name {
  display: block;
  color: #ab1d22;
}""",
        ),
        OnlineEpubStyle(
            id = "title-teal-number-badge",
            kind = OnlineEpubStyleKind.Title,
            name = "青蓝编号章题",
            description = "左对齐双行标题，章节序号使用青蓝底色编号块。",
            builtIn = true,
            css = """.te-chapter-title {
  font-size: 1em;
  color: #0d335d;
  text-align: left;
  line-height: 1.3;
  padding: 0 4px;
  margin: 0 0 2em;
  text-indent: 0;
  duokan-text-indent: 0;
}

.te-chapter-number {
  display: inline-block;
  margin-bottom: 0.6em;
  padding: 0.5px 2px;
  color: #ffffff;
  font-size: x-small;
  background-color: #1d5a6c;
  border-radius: 0;
  border: 1px solid #684c7f;
}

.te-chapter-name {
  display: block;
  color: #0d335d;
}""",
        ),
        OnlineEpubStyle(
            id = "title-cinematic-slab",
            kind = OnlineEpubStyleKind.Title,
            name = "电影字幕章题",
            description = "深蓝主标题配金色序号线，克制、有画面感，适合悬疑、都市、科幻。",
            builtIn = true,
            css = """.te-chapter-title {
  width: 82%;
  margin: 1.5em auto 2.6em;
  padding: 0.75em 0 0.8em;
  border-top: 2px solid #1f2937;
  border-bottom: 1px solid #c8a65a;
  color: #172033;
  text-align: center;
  text-indent: 0;
  line-height: 1.25;
}

.te-chapter-number {
  display: block;
  margin-bottom: 0.48em;
  color: #b8860b;
  font-size: 0.72em;
  font-weight: 700;
}

.te-chapter-name {
  display: block;
  color: #172033;
  font-size: 1.26em;
  font-weight: 900;
}""",
        ),
        OnlineEpubStyle(
            id = "title-scroll-border",
            kind = OnlineEpubStyleKind.Title,
            name = "书卷双线章题",
            description = "暖色双线和居中标题，像纸本装帧页，适合古风、奇幻、文学向作品。",
            builtIn = true,
            css = """.te-chapter-title {
  width: 78%;
  margin: 2em auto 2.7em;
  padding: 0.85em 0.3em;
  border-top: 1px solid #b58b52;
  border-bottom: 1px solid #b58b52;
  background: #fffaf0;
  color: #2b2118;
  text-align: center;
  text-indent: 0;
  line-height: 1.28;
}

.te-chapter-number {
  display: block;
  margin-bottom: 0.5em;
  color: #8a6a3d;
  font-size: 0.72em;
  font-weight: 600;
}

.te-chapter-name {
  display: block;
  color: #2b2118;
  font-size: 1.18em;
  font-weight: 800;
}""",
        ),
        OnlineEpubStyle(
            id = "title-seal-left",
            kind = OnlineEpubStyleKind.Title,
            name = "朱印左标题",
            description = "左对齐章名搭配朱色印章式序号，适合武侠、历史、东方幻想。",
            builtIn = true,
            css = """.te-chapter-title {
  margin: 1.6em 0 2.4em;
  padding-left: 0.3em;
  color: #222222;
  text-align: left;
  text-indent: 0;
  line-height: 1.35;
}

.te-chapter-number {
  display: inline-block;
  margin-bottom: 0.55em;
  padding: 0.22em 0.45em;
  border: 1px solid #a32020;
  background: #a32020;
  color: #fffdf7;
  font-size: 0.72em;
  font-weight: 800;
}

.te-chapter-name {
  display: block;
  border-left: 4px solid #a32020;
  padding-left: 0.65em;
  color: #222222;
  font-size: 1.18em;
  font-weight: 900;
}""",
        ),
        OnlineEpubStyle(
            id = "title-soft-magazine",
            kind = OnlineEpubStyleKind.Title,
            name = "清爽杂志章题",
            description = "浅青分隔与大留白，阅读感轻，适合现代、治愈、日常题材。",
            builtIn = true,
            css = """.te-chapter-title {
  width: 86%;
  margin: 1.8em auto 2.8em;
  padding: 0 0 0.9em;
  border-bottom: 3px double #8bc5c1;
  color: #165a64;
  text-align: center;
  text-indent: 0;
  line-height: 1.32;
}

.te-chapter-number {
  display: block;
  margin-bottom: 0.55em;
  color: #64748b;
  font-size: 0.72em;
  font-weight: 600;
}

.te-chapter-name {
  display: block;
  color: #165a64;
  font-size: 1.16em;
  font-weight: 800;
}""",
        ),
        OnlineEpubStyle(
            id = "title-night-card",
            kind = OnlineEpubStyleKind.Title,
            name = "夜色章卡",
            description = "深色标题卡片与亮色序号，适合紧张、悬疑、赛博或暗色题材。",
            builtIn = true,
            css = """.te-chapter-title {
  width: 84%;
  margin: 1.6em auto 2.6em;
  padding: 0.9em 1em;
  box-sizing: border-box;
  background: #172033;
  color: #f8fafc;
  text-align: left;
  text-indent: 0;
  line-height: 1.35;
}

.te-chapter-number {
  display: block;
  margin-bottom: 0.48em;
  color: #f5c542;
  font-size: 0.72em;
  font-weight: 800;
}

.te-chapter-name {
  display: block;
  color: #f8fafc;
  font-size: 1.16em;
  font-weight: 900;
}""",
        ),
        OnlineEpubStyle(
            id = "title-minimal-quiet",
            kind = OnlineEpubStyleKind.Title,
            name = "素净留白章题",
            description = "极简居中标题，低装饰、强留白，适合长篇阅读和正文密集作品。",
            builtIn = true,
            css = """.te-chapter-title {
  margin: 2.6em auto 3.2em;
  padding-bottom: 0.9em;
  width: 70%;
  border-bottom: 1px solid #d0d7de;
  color: #1f2937;
  text-align: center;
  text-indent: 0;
  line-height: 1.32;
}

.te-chapter-number {
  display: block;
  margin-bottom: 0.45em;
  color: #94a3b8;
  font-size: 0.7em;
  font-weight: 600;
}

.te-chapter-name {
  display: block;
  color: #1f2937;
  font-size: 1.08em;
  font-weight: 700;
}""",
        ),
        OnlineEpubStyle(
            id = "illustration-centered-caption",
            kind = OnlineEpubStyleKind.Illustration,
            name = "居中图注插图",
            description = "常见的正文居中插图，限制宽度并在下方显示简洁图注。",
            builtIn = true,
            markup = """<figure class="te-illustration">
  <img class="te-illustration-image" src="../Images/illustration-01.jpg" alt="旧站台夜景" />
  <figcaption class="te-illustration-caption">图 1　旧站台的最后一班列车</figcaption>
</figure>""",
            css = """.te-illustration {
  width: 86%;
  margin: 1.6em auto;
  padding: 0;
  text-align: center;
  text-indent: 0;
  duokan-text-indent: 0;
  page-break-inside: avoid;
}

.te-illustration-image {
  display: block;
  width: 100%;
  height: auto;
  margin: 0 auto;
  border: 0;
}

.te-illustration-caption {
  display: block;
  margin-top: 0.65em;
  color: #64748b;
  font-size: 0.78em;
  line-height: 1.5;
  text-align: center;
  text-indent: 0;
}""",
        ),
        OnlineEpubStyle(
            id = "illustration-paper-card",
            kind = OnlineEpubStyleKind.Illustration,
            name = "纸张卡片插图",
            description = "带留白、细边框和轻阴影的卡片式插图，适合信件、档案和回忆画面。",
            builtIn = true,
            markup = """<figure class="te-illustration">
  <img class="te-illustration-image" src="../Images/archive-photo.jpg" alt="档案照片" />
  <figcaption class="te-illustration-caption">档案编号 A-17</figcaption>
</figure>""",
            css = """.te-illustration {
  width: 82%;
  margin: 1.8em auto;
  padding: 0.7em;
  box-sizing: border-box;
  border: 1px solid #d7c8ad;
  background: #fffaf0;
  box-shadow: 0 0.2em 0.8em rgba(72, 52, 34, 0.14);
  text-align: center;
  text-indent: 0;
  page-break-inside: avoid;
}

.te-illustration-image {
  display: block;
  width: 100%;
  height: auto;
  border: 0;
}

.te-illustration-caption {
  margin-top: 0.7em;
  color: #6f5638;
  font-size: 0.76em;
  line-height: 1.5;
  text-align: center;
}""",
        ),
        OnlineEpubStyle(
            id = "illustration-full-bleed",
            kind = OnlineEpubStyleKind.Illustration,
            name = "通栏出血插图",
            description = "横向铺满版心并向两侧出血，适合章节中段的大场景和地图。",
            builtIn = true,
            markup = """<figure class="te-illustration">
  <img class="te-illustration-image" src="../Images/panorama-01.jpg" alt="城市全景" />
</figure>""",
            css = """.te-illustration {
  margin: 1.8em -1.5em;
  padding: 0;
  line-height: 0;
  text-align: center;
  text-indent: 0;
  duokan-text-indent: 0;
  duokan-bleed: leftright;
  page-break-inside: avoid;
}

.te-illustration-image {
  display: block;
  width: 100%;
  max-width: none;
  height: auto;
  margin: 0;
  border: 0;
}""",
        ),
        OnlineEpubStyle(
            id = "illustration-annotation-popup",
            kind = OnlineEpubStyleKind.Illustration,
            name = "注释点击弹图",
            description = "点击正文词语或注释链接，以无脚本弹层显示对应插图。",
            builtIn = true,
            markup = """<input id="te-annotation-toggle-1" class="te-annotation-toggle" type="checkbox" />
<p class="te-paragraph">她在<label class="te-annotation-trigger" for="te-annotation-toggle-1">旧站台<a id="te-annotation-ref-1" class="duokan-footnote" href="#te-annotation-note-1" epub:type="noteref" role="doc-noteref" aria-controls="te-annotation-note-1">〔查看插图〕</a></label>旁停下脚步。</p>
<aside id="te-annotation-note-1" class="te-annotation-popup duokan-footnote-item" epub:type="footnote" role="doc-footnote">
  <label class="te-annotation-backdrop" for="te-annotation-toggle-1" aria-label="关闭插图"></label>
  <figure class="te-annotation-figure">
    <label class="te-annotation-close" for="te-annotation-toggle-1" aria-label="关闭">×</label>
    <img class="te-annotation-image" src="../Images/note-station.jpg" alt="旧站台" />
    <figcaption class="te-annotation-caption">旧站台改造前的资料照片</figcaption>
    <a class="duokan-footnote-back" href="#te-annotation-ref-1">返回</a>
  </figure>
</aside>""",
            css = """.te-annotation-trigger {
  color: #17699a;
  text-decoration: none;
  border-bottom: 1px dotted currentColor;
  cursor: pointer;
}

.te-annotation-toggle {
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
  overflow: hidden;
  pointer-events: none;
}

.te-annotation-popup {
  display: none;
  position: fixed;
  top: 0;
  right: 0;
  bottom: 0;
  left: 0;
  z-index: 999;
  align-items: center;
  justify-content: center;
  padding: 1.2em;
  box-sizing: border-box;
  text-indent: 0;
}

.te-annotation-toggle:checked ~ .te-annotation-popup {
  display: flex;
}

.te-annotation-backdrop {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  left: 0;
  background: rgba(15, 23, 42, 0.78);
}

.te-annotation-figure {
  position: relative;
  z-index: 1;
  width: 90%;
  max-width: 34em;
  max-height: 88vh;
  margin: 0;
  padding: 0.8em;
  box-sizing: border-box;
  overflow: auto;
  border-radius: 0.35em;
  background: #fffdf8;
  text-align: center;
}

.te-annotation-image {
  display: block;
  max-width: 100%;
  max-height: 70vh;
  width: auto;
  height: auto;
  margin: 0 auto;
}

.te-annotation-caption {
  margin: 0.7em 2em 0;
  color: #475569;
  font-size: 0.78em;
  line-height: 1.5;
}

.te-annotation-close {
  position: absolute;
  top: 0.35em;
  right: 0.45em;
  width: 1.8em;
  height: 1.8em;
  border-radius: 50%;
  background: #172033;
  color: #ffffff;
  font: bold 1em/1.8 sans-serif;
  text-align: center;
  cursor: pointer;
}""",
        ),
        OnlineEpubStyle(
            id = "transition-fg1-stars",
            kind = OnlineEpubStyleKind.Transition,
            name = "居中三星转场",
            description = "制作功能内置的经典居中 ※※※，兼容旧 class fg1 与标准 te-divider-line。",
            builtIn = true,
            markup = """<p class="fg1">※※※</p>""",
            css = """p.fg1,
p.te-divider-line {
  margin: 1.4em 0;
  padding: 0;
  color: #4b5563;
  font-size: 0.9em;
  line-height: 1.4;
  letter-spacing: 0.6em;
  text-align: center;
  text-indent: 0;
  duokan-text-indent: 0;
}""",
        ),
        OnlineEpubStyle(
            id = "transition-css-diamond",
            kind = OnlineEpubStyleKind.Transition,
            name = "CSS 菱形转场",
            description = "不使用文字和图片，仅用伪元素绘制三枚渐次菱形。",
            builtIn = true,
            markup = """<p class="te-transition te-transition--diamond" aria-label="场景转换"></p>""",
            css = """.te-transition--diamond {
  margin: 1.7em 0;
  padding: 0;
  height: 1em;
  line-height: 1;
  text-align: center;
  text-indent: 0;
}

.te-transition--diamond::before {
  content: "◆ ◇ ◆";
  color: #8a6a3d;
  font-size: 0.72em;
  letter-spacing: 0.5em;
}""",
        ),
        OnlineEpubStyle(
            id = "transition-text-label",
            kind = OnlineEpubStyleKind.Transition,
            name = "文字线条转场",
            description = "在左右细线之间显示“与此同时”等短文本。",
            builtIn = true,
            markup = """<p class="te-transition te-transition--text"><span class="te-transition-text">与此同时</span></p>""",
            css = """.te-transition--text {
  display: flex;
  align-items: center;
  gap: 0.8em;
  margin: 1.8em 0;
  color: #64748b;
  font-size: 0.76em;
  line-height: 1.4;
  text-align: center;
  text-indent: 0;
}

.te-transition--text::before,
.te-transition--text::after {
  content: "";
  flex: 1;
  height: 1px;
  background: #cbd5e1;
}

.te-transition-text {
  white-space: nowrap;
}""",
        ),
        OnlineEpubStyle(
            id = "transition-css-fade-line",
            kind = OnlineEpubStyleKind.Transition,
            name = "CSS 渐隐短线",
            description = "无文本的轻量渐隐横线，适合现代、日常和长篇阅读。",
            builtIn = true,
            markup = """<p class="te-transition te-transition--fade-line" aria-label="场景转换"></p>""",
            css = """.te-transition--fade-line {
  width: 58%;
  height: 1px;
  margin: 1.9em auto;
  padding: 0;
  background: linear-gradient(90deg, transparent, #94a3b8 22%, #94a3b8 78%, transparent);
  line-height: 0;
  text-indent: 0;
}""",
        ),
        OnlineEpubStyle(
            id = "transition-image-divider",
            kind = OnlineEpubStyleKind.Transition,
            name = "图片装饰转场",
            description = "使用独立 PNG、JPG 或 SVG 小图替代孤立省略号。",
            builtIn = true,
            markup = """<div class="te-divider-image">
  <img class="te-divider-img" src="../Images/divider.png" alt="场景转换" />
</div>""",
            css = """.te-divider-image {
  margin: 1.6em 0;
  padding: 0;
  line-height: 1;
  text-align: center;
  text-indent: 0;
  duokan-text-indent: 0;
  page-break-inside: avoid;
}

.te-divider-img {
  display: inline-block;
  width: 12em;
  max-width: 72%;
  height: auto;
  border: 0;
  vertical-align: middle;
}""",
        ),
        OnlineEpubStyle(
            id = "header-template-bottom-fade",
            kind = OnlineEpubStyleKind.Header,
            name = "底部渐隐贴边头图",
            description = "底部透明渐隐，适合让用户上传的头图自然过渡到正文留白。",
            builtIn = true,
            maskAsset = "epub_header_mask/header-template-bottom-fade.png",
            sampleWidth = 1080,
            sampleHeight = 750,
            css = """.te-header-figure {
  margin: 0 -1.5em 1.6em;
  padding: 0;
  position: relative;
  overflow: hidden;
  line-height: 0;
  text-align: center;
  text-indent: 0;
  duokan-text-indent: 0;
  duokan-bleed: lefttopright;
}

.te-header-image {
  display: block;
  width: 100%;
  max-width: none;
  height: auto;
  object-fit: cover;
}

.te-header-caption {
  display: none;
}""",
        ),
        OnlineEpubStyle(
            id = "header-template-torn-edge",
            kind = OnlineEpubStyleKind.Header,
            name = "底部撕边贴边头图",
            description = "底部不规则透明撕边，适合有动作感或场景切换感的章节头图。",
            builtIn = true,
            maskAsset = "epub_header_mask/header-template-torn-edge.png",
            sampleWidth = 1080,
            sampleHeight = 784,
            css = """.te-header-figure {
  margin: 0 -1.5em 1.6em;
  padding: 0;
  position: relative;
  overflow: hidden;
  line-height: 0;
  text-align: center;
  text-indent: 0;
  duokan-text-indent: 0;
  duokan-bleed: lefttopright;
}

.te-header-image {
  display: block;
  width: 100%;
  max-width: none;
  height: auto;
  object-fit: cover;
}

.te-header-caption {
  display: none;
}""",
        ),
        OnlineEpubStyle(
            id = "header-template-scatter-edge",
            kind = OnlineEpubStyleKind.Header,
            name = "底部散点贴边头图",
            description = "底部散点透明过渡，适合较柔和的横向头图样式。",
            builtIn = true,
            maskAsset = "epub_header_mask/header-template-scatter-edge.png",
            sampleWidth = 1080,
            sampleHeight = 608,
            css = """.te-header-figure {
  margin: 0 -1.5em 1.6em;
  padding: 0;
  position: relative;
  overflow: hidden;
  line-height: 0;
  text-align: center;
  text-indent: 0;
  duokan-text-indent: 0;
  duokan-bleed: lefttopright;
}

.te-header-image {
  display: block;
  width: 100%;
  max-width: none;
  height: auto;
  object-fit: cover;
}

.te-header-caption {
  display: none;
}""",
        ),
        OnlineEpubStyle(
            id = "header-template-ink-edge",
            kind = OnlineEpubStyleKind.Header,
            name = "底部墨痕贴边头图",
            description = "底部墨痕状透明留白，适合边缘更硬朗的章节头图。",
            builtIn = true,
            maskAsset = "epub_header_mask/header-template-ink-edge.png",
            sampleWidth = 1080,
            sampleHeight = 608,
            css = """.te-header-figure {
  margin: 0 -1.5em 1.6em;
  padding: 0;
  position: relative;
  overflow: hidden;
  line-height: 0;
  text-align: center;
  text-indent: 0;
  duokan-text-indent: 0;
  duokan-bleed: lefttopright;
}

.te-header-image {
  display: block;
  width: 100%;
  max-width: none;
  height: auto;
  object-fit: cover;
}

.te-header-caption {
  display: none;
}""",
        ),
        OnlineEpubStyle(
            id = "header-template-diagonal-brush",
            kind = OnlineEpubStyleKind.Header,
            name = "斜向笔刷贴边头图",
            description = "斜向笔刷透明边缘，适合需要强烈斜切构图的头图样式。",
            builtIn = true,
            maskAsset = "epub_header_mask/header-template-diagonal-brush.png",
            sampleWidth = 1080,
            sampleHeight = 608,
            css = """.te-header-figure {
  margin: 0 -1.5em 1.6em;
  padding: 0;
  position: relative;
  overflow: hidden;
  line-height: 0;
  text-align: center;
  text-indent: 0;
  duokan-text-indent: 0;
  duokan-bleed: lefttopright;
}

.te-header-image {
  display: block;
  width: 100%;
  max-width: none;
  height: auto;
  object-fit: cover;
}

.te-header-caption {
  display: none;
}""",
        ),
        OnlineEpubStyle(
            id = "header-template-delivery-bike-collage",
            kind = OnlineEpubStyleKind.Header,
            name = "左叠拼片散边头图",
            description = "左侧拼片式透明轮廓，主体向中下部展开，适合需要留白和碎片感的章节头图。",
            builtIn = true,
            maskAsset = "epub_header_mask/header-template-delivery-bike-collage.png",
            sampleWidth = 1080,
            sampleHeight = 810,
            css = """.te-header-figure {
  margin: 0 -1.5em 1.6em;
  padding: 0;
  position: relative;
  overflow: hidden;
  line-height: 0;
  text-align: center;
  text-indent: 0;
  duokan-text-indent: 0;
  duokan-bleed: lefttopright;
}

.te-header-image {
  display: block;
  width: 100%;
  max-width: none;
  height: auto;
  object-fit: cover;
}

.te-header-caption {
  display: none;
}""",
        ),
        OnlineEpubStyle(
            id = "header-template-cloud-gate-ink-banner",
            kind = OnlineEpubStyleKind.Header,
            name = "墨染横幅散边头图",
            description = "横幅式透明蒙版，四周带墨染散边，适合需要大场景铺陈和卷首仪式感的章节头图。",
            builtIn = true,
            maskAsset = "epub_header_mask/header-template-cloud-gate-ink-banner.png",
            sampleWidth = 1080,
            sampleHeight = 650,
            css = """.te-header-figure {
  margin: 0 -1.5em 1.6em;
  padding: 0;
  position: relative;
  overflow: hidden;
  line-height: 0;
  text-align: center;
  text-indent: 0;
  duokan-text-indent: 0;
  duokan-bleed: lefttopright;
}

.te-header-image {
  display: block;
  width: 100%;
  max-width: none;
  height: auto;
  object-fit: cover;
}

.te-header-caption {
  display: none;
}""",
        ),
        OnlineEpubStyle(
            id = "volume-classic-red",
            kind = OnlineEpubStyleKind.Volume,
            name = "经典红卷",
            description = "居中双行标题，卷序号偏暗，卷名使用红色强调。",
            builtIn = true,
            css = """.te-volume-title {
  text-align: center;
  margin: 2em 0 3em;
  font-size: 1.2em;
  font-weight: 900;
  color: #c2181e;
}

.te-volume-number {
  display: block;
  color: #413245;
  font-size: 0.82em;
  line-height: 1.35;
}

.te-volume-name {
  display: block;
  color: #c2181e;
}""",
        ),
        OnlineEpubStyle(
            id = "volume-ink-line",
            kind = OnlineEpubStyleKind.Volume,
            name = "墨线卷题",
            description = "黑白细线分隔，适合正文密集、低装饰的作品。",
            builtIn = true,
            css = """.te-volume-title {
  margin: 2.4em auto 2.8em;
  padding: 0.9em 0;
  width: 82%;
  border-top: 1px solid #1f2937;
  border-bottom: 1px solid #1f2937;
  color: #111827;
  text-align: center;
  text-indent: 0;
}

.te-volume-number {
  display: block;
  margin-bottom: 0.45em;
  color: #6b7280;
  font-size: 0.78em;
}

.te-volume-name {
  display: block;
  font-size: 1.18em;
  font-weight: 700;
}""",
        ),
        OnlineEpubStyle(
            id = "volume-purple-red-emphasis",
            kind = OnlineEpubStyleKind.Volume,
            name = "暗紫红重点卷题",
            description = "居中双行标题，卷序号用暗紫色，卷名用红色强调。",
            builtIn = true,
            css = """.te-volume-title {
  text-align: center;
  font-weight: 900;
  font-size: 0.8em;
  margin: 1em 0 3em;
  color: #413245;
  line-height: 1.3;
  text-indent: 0;
  duokan-text-indent: 0;
}

.te-volume-number {
  display: block;
  color: #413245;
}

.te-volume-name {
  display: block;
  font-size: 1.2em;
  font-weight: 900;
  color: #c2181e;
}""",
        ),
        OnlineEpubStyle(
            id = "volume-teal-left-heading",
            kind = OnlineEpubStyleKind.Volume,
            name = "青蓝左齐卷题",
            description = "左对齐双行标题，卷序号为黑色小字，卷名为青蓝色。",
            builtIn = true,
            css = """.te-volume-title {
  font-size: 1.1em;
  line-height: 1.2;
  font-weight: bold;
  text-align: left;
  margin: 1em 0;
  padding-bottom: 1em;
  text-indent: 0;
  duokan-text-indent: 0;
  color: #02586d;
}

.te-volume-number {
  display: block;
  margin-bottom: 0.35em;
  font-size: 0.8em;
  color: #000;
  font-weight: bold;
}

.te-volume-name {
  display: block;
  color: #02586d;
  font-weight: bold;
}""",
        ),
        OnlineEpubStyle(
            id = "volume-vermilion-center",
            kind = OnlineEpubStyleKind.Volume,
            name = "朱红居中卷题",
            description = "居中双行标题，序号较小，标题名使用朱红色强调。",
            builtIn = true,
            css = """.te-volume-title {
  margin-top: 0;
  margin-bottom: 1.5em;
  color: #ab1d22;
  font-size: 1.2em;
  line-height: 1.3;
  text-align: center;
  text-indent: 0;
  duokan-text-indent: 0;
}

.te-volume-number {
  display: block;
  font-size: 0.7em;
  color: #ab1d22;
  font-weight: 500;
}

.te-volume-name {
  display: block;
  color: #ab1d22;
}""",
        ),
        OnlineEpubStyle(
            id = "volume-teal-number-badge",
            kind = OnlineEpubStyleKind.Volume,
            name = "青蓝编号卷题",
            description = "左对齐双行标题，卷序号使用青蓝底色编号块。",
            builtIn = true,
            css = """.te-volume-title {
  font-size: 1em;
  color: #0d335d;
  text-align: left;
  line-height: 1.3;
  padding: 0 4px;
  margin: 0 0 2em;
  text-indent: 0;
  duokan-text-indent: 0;
}

.te-volume-number {
  display: inline-block;
  margin-bottom: 0.6em;
  padding: 0.5px 2px;
  color: #ffffff;
  font-size: x-small;
  background-color: #1d5a6c;
  border-radius: 0;
  border: 1px solid #684c7f;
}

.te-volume-name {
  display: block;
  color: #0d335d;
}""",
        ),
        OnlineEpubStyle(
            id = "volume-cinematic-slab",
            kind = OnlineEpubStyleKind.Volume,
            name = "电影字幕卷题",
            description = "深蓝主标题配金色序号线，克制、有画面感，适合悬疑、都市、科幻。",
            builtIn = true,
            css = """.te-volume-title {
  width: 82%;
  margin: 1.5em auto 2.6em;
  padding: 0.75em 0 0.8em;
  border-top: 2px solid #1f2937;
  border-bottom: 1px solid #c8a65a;
  color: #172033;
  text-align: center;
  text-indent: 0;
  line-height: 1.25;
}

.te-volume-number {
  display: block;
  margin-bottom: 0.48em;
  color: #b8860b;
  font-size: 0.72em;
  font-weight: 700;
}

.te-volume-name {
  display: block;
  color: #172033;
  font-size: 1.26em;
  font-weight: 900;
}""",
        ),
        OnlineEpubStyle(
            id = "volume-scroll-border",
            kind = OnlineEpubStyleKind.Volume,
            name = "书卷双线卷题",
            description = "暖色双线和居中标题，像纸本装帧页，适合古风、奇幻、文学向作品。",
            builtIn = true,
            css = """.te-volume-title {
  width: 78%;
  margin: 2em auto 2.7em;
  padding: 0.85em 0.3em;
  border-top: 1px solid #b58b52;
  border-bottom: 1px solid #b58b52;
  background: #fffaf0;
  color: #2b2118;
  text-align: center;
  text-indent: 0;
  line-height: 1.28;
}

.te-volume-number {
  display: block;
  margin-bottom: 0.5em;
  color: #8a6a3d;
  font-size: 0.72em;
  font-weight: 600;
}

.te-volume-name {
  display: block;
  color: #2b2118;
  font-size: 1.18em;
  font-weight: 800;
}""",
        ),
        OnlineEpubStyle(
            id = "volume-seal-left",
            kind = OnlineEpubStyleKind.Volume,
            name = "朱印左标题",
            description = "左对齐章名搭配朱色印章式序号，适合武侠、历史、东方幻想。",
            builtIn = true,
            css = """.te-volume-title {
  margin: 1.6em 0 2.4em;
  padding-left: 0.3em;
  color: #222222;
  text-align: left;
  text-indent: 0;
  line-height: 1.35;
}

.te-volume-number {
  display: inline-block;
  margin-bottom: 0.55em;
  padding: 0.22em 0.45em;
  border: 1px solid #a32020;
  background: #a32020;
  color: #fffdf7;
  font-size: 0.72em;
  font-weight: 800;
}

.te-volume-name {
  display: block;
  border-left: 4px solid #a32020;
  padding-left: 0.65em;
  color: #222222;
  font-size: 1.18em;
  font-weight: 900;
}""",
        ),
        OnlineEpubStyle(
            id = "volume-soft-magazine",
            kind = OnlineEpubStyleKind.Volume,
            name = "清爽杂志卷题",
            description = "浅青分隔与大留白，阅读感轻，适合现代、治愈、日常题材。",
            builtIn = true,
            css = """.te-volume-title {
  width: 86%;
  margin: 1.8em auto 2.8em;
  padding: 0 0 0.9em;
  border-bottom: 3px double #8bc5c1;
  color: #165a64;
  text-align: center;
  text-indent: 0;
  line-height: 1.32;
}

.te-volume-number {
  display: block;
  margin-bottom: 0.55em;
  color: #64748b;
  font-size: 0.72em;
  font-weight: 600;
}

.te-volume-name {
  display: block;
  color: #165a64;
  font-size: 1.16em;
  font-weight: 800;
}""",
        ),
        OnlineEpubStyle(
            id = "volume-night-card",
            kind = OnlineEpubStyleKind.Volume,
            name = "夜色章卡",
            description = "深色标题卡片与亮色序号，适合紧张、悬疑、赛博或暗色题材。",
            builtIn = true,
            css = """.te-volume-title {
  width: 84%;
  margin: 1.6em auto 2.6em;
  padding: 0.9em 1em;
  box-sizing: border-box;
  background: #172033;
  color: #f8fafc;
  text-align: left;
  text-indent: 0;
  line-height: 1.35;
}

.te-volume-number {
  display: block;
  margin-bottom: 0.48em;
  color: #f5c542;
  font-size: 0.72em;
  font-weight: 800;
}

.te-volume-name {
  display: block;
  color: #f8fafc;
  font-size: 1.16em;
  font-weight: 900;
}""",
        ),
        OnlineEpubStyle(
            id = "volume-minimal-quiet",
            kind = OnlineEpubStyleKind.Volume,
            name = "素净留白卷题",
            description = "极简居中标题，低装饰、强留白，适合长篇阅读和正文密集作品。",
            builtIn = true,
            css = """.te-volume-title {
  margin: 2.6em auto 3.2em;
  padding-bottom: 0.9em;
  width: 70%;
  border-bottom: 1px solid #d0d7de;
  color: #1f2937;
  text-align: center;
  text-indent: 0;
  line-height: 1.32;
}

.te-volume-number {
  display: block;
  margin-bottom: 0.45em;
  color: #94a3b8;
  font-size: 0.7em;
  font-weight: 600;
}

.te-volume-name {
  display: block;
  color: #1f2937;
  font-size: 1.08em;
  font-weight: 700;
}""",
        ),
    )

    fun byKind(kind: OnlineEpubStyleKind): List<OnlineEpubStyle> = BUILT_INS.filter { it.kind == kind }

    fun byId(id: String): OnlineEpubStyle? = BUILT_INS.firstOrNull { it.id == id }
}
