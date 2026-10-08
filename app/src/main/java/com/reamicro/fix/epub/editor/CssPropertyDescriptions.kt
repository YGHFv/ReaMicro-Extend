package com.reamicro.fix.epub.editor

import java.util.Locale

internal object CssPropertyDescriptions {
    private val labels = mapOf(
        "color" to "文字颜色", "background" to "背景", "background-color" to "背景颜色",
        "background-image" to "背景图片", "background-size" to "背景尺寸",
        "background-position" to "背景位置", "background-repeat" to "背景重复方式",
        "background-attachment" to "背景滚动方式", "background-clip" to "背景绘制范围",
        "background-origin" to "背景定位区域",
        "font" to "字体综合设置", "font-family" to "字体族", "font-size" to "字号",
        "font-weight" to "字重", "font-style" to "字形样式", "font-variant" to "字体变体",
        "font-stretch" to "字体伸缩", "font-feature-settings" to "字体特性",
        "font-variation-settings" to "可变字体轴", "font-display" to "字体加载显示策略",
        "line-height" to "行高", "letter-spacing" to "字间距", "word-spacing" to "词间距",
        "text-align" to "文本对齐", "text-indent" to "首行缩进", "text-decoration" to "文字装饰",
        "text-decoration-color" to "文字装饰颜色", "text-transform" to "大小写转换",
        "text-shadow" to "文字阴影", "text-overflow" to "文字溢出处理",
        "white-space" to "空白与换行处理", "word-break" to "单词断行",
        "overflow-wrap" to "溢出换行", "word-wrap" to "长词换行", "hyphens" to "连字符断词",
        "writing-mode" to "书写方向", "text-orientation" to "竖排文字方向",
        "direction" to "文本方向", "unicode-bidi" to "双向文本处理",
        "vertical-align" to "垂直对齐", "line-clamp" to "显示行数限制",
        "display" to "显示方式", "visibility" to "可见性", "opacity" to "不透明度",
        "position" to "定位方式", "top" to "顶部位置", "right" to "右侧位置",
        "bottom" to "底部位置", "left" to "左侧位置", "inset" to "定位偏移", "z-index" to "层叠顺序",
        "width" to "宽度", "height" to "高度", "min-width" to "最小宽度", "max-width" to "最大宽度",
        "min-height" to "最小高度", "max-height" to "最大高度",
        "margin" to "外边距", "margin-top" to "上外边距", "margin-right" to "右外边距",
        "margin-bottom" to "下外边距", "margin-left" to "左外边距",
        "margin-inline" to "行内方向外边距", "margin-block" to "块方向外边距",
        "padding" to "内边距", "padding-top" to "上内边距", "padding-right" to "右内边距",
        "padding-bottom" to "下内边距", "padding-left" to "左内边距",
        "padding-inline" to "行内方向内边距", "padding-block" to "块方向内边距",
        "border" to "边框", "border-width" to "边框宽度", "border-color" to "边框颜色",
        "border-style" to "边框样式", "border-radius" to "圆角",
        "border-top" to "上边框", "border-right" to "右边框",
        "border-bottom" to "下边框", "border-left" to "左边框",
        "outline" to "轮廓线", "box-sizing" to "盒模型计算方式", "box-shadow" to "盒子阴影",
        "overflow" to "内容溢出处理", "overflow-x" to "水平溢出处理", "overflow-y" to "垂直溢出处理",
        "float" to "浮动", "clear" to "清除浮动", "object-fit" to "替换内容缩放方式",
        "object-position" to "替换内容位置",
        "page-break-before" to "元素前分页", "page-break-after" to "元素后分页",
        "page-break-inside" to "元素内分页", "break-before" to "元素前分隔",
        "break-after" to "元素后分隔", "break-inside" to "元素内分隔",
        "widows" to "页首最少保留行数", "orphans" to "页尾最少保留行数",
        "list-style" to "列表样式", "list-style-type" to "列表标记类型",
        "list-style-position" to "列表标记位置", "list-style-image" to "列表标记图片",
        "content" to "生成内容", "quotes" to "引用符号", "counter-reset" to "重置计数器",
        "counter-increment" to "递增计数器",
        "flex" to "弹性项目", "flex-direction" to "弹性布局方向", "flex-wrap" to "弹性换行",
        "flex-grow" to "弹性扩展比例", "flex-shrink" to "弹性收缩比例", "flex-basis" to "弹性基准尺寸",
        "align-items" to "交叉轴项目对齐", "align-self" to "单项交叉轴对齐",
        "align-content" to "多行交叉轴对齐", "justify-content" to "主轴内容对齐",
        "justify-items" to "行内轴项目对齐", "gap" to "项目间距", "row-gap" to "行间距",
        "column-gap" to "列间距", "grid-template-columns" to "网格列定义",
        "grid-template-rows" to "网格行定义", "grid-column" to "网格列位置", "grid-row" to "网格行位置",
        "columns" to "多列排版", "column-count" to "列数", "column-width" to "列宽",
        "transform" to "几何变换", "transform-origin" to "变换原点", "transition" to "过渡效果",
        "animation" to "动画", "clip-path" to "裁剪路径", "fill" to "填充颜色",
        "stroke" to "描边颜色", "stroke-width" to "描边宽度",
        "src" to "字体资源地址", "unicode-range" to "字体字符范围",
    )

    fun describe(property: String, nativeSummary: String?): String {
        if (nativeSummary != null && nativeSummary.any { it in '\u3400'..'\u9fff' }) return nativeSummary
        if (property.startsWith("--")) return "自定义属性"
        val key = property.lowercase(Locale.ROOT)
        return labels[key] ?: labels[key.removePrefix("-webkit-").removePrefix("-moz-").removePrefix("-epub-")]
            ?: "CSS 属性"
    }
}
