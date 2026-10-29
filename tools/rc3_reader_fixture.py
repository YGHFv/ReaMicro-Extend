#!/usr/bin/env python3
"""生成多章测试 EPUB；搜索、翻页及编辑只能操作这些自建内容。"""

from zipfile import ZipFile, ZIP_DEFLATED, ZIP_STORED
from rc3_full_audit import OUT, adb

path = OUT / "RC3-Audit-Reader.epub"
with ZipFile(path, "x", compression=ZIP_DEFLATED) as archive:
    archive.writestr("mimetype", "application/epub+zip", compress_type=ZIP_STORED)
    archive.writestr("META-INF/container.xml",
        '<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">'
        '<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>'
        '</rootfiles></container>')
    archive.writestr("OEBPS/content.opf",
        '<package xmlns="http://www.idpf.org/2007/opf" unique-identifier="uid" version="3.0">'
        '<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">'
        '<dc:identifier id="uid">urn:uuid:5239235f-e54f-421a-a6c8-5d9ea0000029</dc:identifier>'
        '<dc:title>RC3 Audit Reader</dc:title><dc:creator>ReaMicro QA</dc:creator><dc:language>en</dc:language>'
        '<meta property="dcterms:modified">2026-10-29T02:00:00Z</meta></metadata>'
        '<manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>'
        '<item id="one" href="one.xhtml" media-type="application/xhtml+xml"/>'
        '<item id="two" href="two.xhtml" media-type="application/xhtml+xml"/></manifest>'
        '<spine><itemref idref="one"/><itemref idref="two"/></spine></package>')
    archive.writestr("OEBPS/nav.xhtml",
        '<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">'
        '<head><title>Contents</title></head><body><nav epub:type="toc"><ol>'
        '<li><a href="one.xhtml">First Chapter</a></li><li><a href="two.xhtml">Second Chapter</a></li>'
        '</ol></nav></body></html>')
    for chapter, name in enumerate(("one", "two"), 1):
        paragraphs = []
        for index in range(30):
            text = f"Chapter {chapter} paragraph {index}. " + (
                "A quiet river winds between the hills. The reader follows the path and turns the page. " * 4)
            if index in (2, 13, 26):
                text = f"FullAuditNeedle{chapter} marker {index}. " + text
            paragraphs.append(f'<p id="p{index}">{text}</p>')
        archive.writestr(f"OEBPS/{name}.xhtml",
            '<html xmlns="http://www.w3.org/1999/xhtml"><head>'
            f'<title>Chapter {chapter}</title></head><body><h1>Chapter {chapter}</h1>'
            + "".join(paragraphs) + "</body></html>")
print(adb("push", str(path), "/sdcard/Download/RC3-Audit-Reader.epub").strip())
