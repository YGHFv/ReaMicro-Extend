"""编译搜索展示纯函数，回归目录层级、分组和预览高亮偏移。"""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile


root = Path(__file__).resolve().parents[1]
cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1"


def jar(group, name, version=None):
    directory = cache / group / name
    if version:
        directory /= version
    return next(directory.rglob("*.jar"))


stdlib = jar("org.jetbrains.kotlin", "kotlin-stdlib", "2.4.20")
compiler = [jar("org.jetbrains.kotlin", name, "2.4.20") for name in (
    "kotlin-compiler-embeddable", "kotlin-build-tools-api", "kotlin-script-runtime", "kotlin-daemon-embeddable")]
compiler += [stdlib, jar("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
             jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0"), jar("org.jetbrains", "annotations")]
java = str(Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")) if os.environ.get("JAVA_HOME") else shutil.which("java")
sources = [root / "app/src/main/java/com/reamicro/fix/hook" / name for name in (
    "ReaderSearchPresentation.kt", "ReaderSearchSnippet.kt", "ReaderSearchBarPlacement.kt", "ReaderSearchListPosition.kt",
    "ReaderSearchRequest.kt", "LatestSearchUpdate.kt", "SearchIndexBudget.kt")]
sources += sorted((root / "tools/search-tests").glob("*.kt"))
# 列表延迟回调不得读取可替换的状态；清空、重搜和报错必须保留旧批次的取值闭包。
dialog = (root / "app/src/main/java/com/reamicro/fix/hook/HostFullTextSearchDialog.kt").read_text(encoding="utf-8")
lazy_content = dialog.split("LazyColumn(", 1)[1].split("// 计数固定", 1)[0]
assert "state.results" not in lazy_content and "state.active" not in lazy_content
assert "val page=state" in dialog and "val results=page.results" in dialog
assert "itemsIndexed(results.subList(section.start,section.endExclusive)" in lazy_content
assert "if(index+1<section.endExclusive)16.dp else 0.dp" in lazy_content
assert "WindowInsets.safeDrawing.only(WindowInsetsSides.Top+WindowInsetsSides.Horizontal)" in dialog
assert "contentPadding=PaddingValues(bottom=bottomInset+16.dp)" in dialog
search = (root / "app/src/main/java/com/reamicro/fix/hook/ReaderHook.Search.kt").read_text(encoding="utf-8")
cancel = search.split("internal fun ReaderHook.cancelSearchPageWork()", 1)[1].split("internal fun", 1)[0]
assert "lastSearchState = null" not in cancel
assert "position?.offset ?: 0" in search and "restored?.keyword.orEmpty()" in search
assert "if (restored?.complete == false && !searchInProgress) submit(restored.keyword, resume = true)" in search
closed = search.split("onClosed = { index, offset ->", 1)[1].split("activeSearchPageUpdate = {", 1)[0]
assert "cancelSearchPageWork()" not in closed
publish = search.split("internal fun ReaderHook.publishSearchState", 1)[1].split("internal fun", 1)[0]
assert "searchNavigationState = state" in publish and "ensureSearchNavigationBar" in publish
assert "selected=active" in dialog and 'Text("当前查看"' in dialog
assert "if(text==null)" in dialog and "window?.setWindowAnimations(0)" in dialog
navigator = (root / "app/src/main/java/com/reamicro/fix/hook/ReaderSearchNavigator.kt").read_text(encoding="utf-8")
assert "private val resolver" not in navigator and "finally { resolver.clear() }" in navigator
returning = search.split("internal fun ReaderHook.returnToSearchOrigin", 1)[1].split("internal fun", 1)[0]
for release in ("cancelSearchPageWork()", "searchDocumentCache.clear()", "searchIndexState = null", "clearPersistedSearchOrigin()", "clearHostSearchJump()"):
    assert returning.index(release) < returning.index("jumpToSearchCfi(")
build = root / "build"
build.mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix="reader-search-", dir=build) as directory:
    output = Path(directory) / "regression.jar"
    subprocess.run([java, "-cp", os.pathsep.join(map(str, compiler)), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                    "-no-stdlib", "-no-reflect", "-classpath", str(stdlib), "-d", str(output), *map(str, sources)],
                   check=True, timeout=120)
    subprocess.run([java, "-cp", os.pathsep.join(map(str, [output, stdlib])),
                    "com.reamicro.fix.hook.ReaderSearchPresentationRegressionKt"], check=True, timeout=30)
