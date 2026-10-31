"""为本地手动编辑源增加可选退场协议，不改变旧源的安装接口。"""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "external-sources/src/main"


def patch(path, changes):
    raw = path.read_bytes()
    newline = "\r\n" if b"\r\n" in raw else "\n"
    for old, new in changes:
        old_bytes = old.replace("\n", newline).encode("utf-8")
        new_bytes = new.replace("\n", newline).encode("utf-8")
        if new_bytes in raw:
            continue
        if old_bytes not in raw:
            raise ValueError(f"Unrecognized local source: {path.name}: {old[:80]}")
        raw = raw.replace(old_bytes, new_bytes)
    path.write_bytes(raw)


patch(SOURCE / "kotlin/com/reamicro/fix/hook/ManualAssociationUiHook.kt", [
    ("        Thread {", "        com.reamicro.fix.reload.ModuleResources.thread {"),
    ("    fun install() {", """    fun releaseForHotReload() {
        hideFloatingButton()
        hideBarcodeHotspot()
        searchGeneration.incrementAndGet()
        currentDraft = null
        baseDraft = null
        currentDetailsContext = null
        currentNavGraphScopeRef = null
        viewModelRef = null
        currentBookRef = null
        releasePublishFlowRef = null
        localBooksById.clear()
        localBooksByCloudId.clear()
        pendingCoverFixes.clear()
        completedCoverFixes.clear()
        latestSearchResults = emptyList()
    }

    fun install() {"""),
])

patch(SOURCE / "java/com/reamicro/fix/external/source/ManualEditFeatureProvider.java", [
    ("import com.reamicro.fix.association.provider.ExternalFeatureProvider;",
     "import com.reamicro.fix.association.provider.ReloadableFeatureProvider;"),
    ("implements ExternalFeatureProvider {", """implements ReloadableFeatureProvider {
    private ManualAssociationUiHook hook;

    @Override
    public boolean canHotReload() {
        return "2.3.3".equals(com.reamicro.fix.core.HostSymbols.INSTANCE.getProfileId());
    }

    @Override
    public void releaseForHotReload() {
        if (hook != null) hook.releaseForHotReload();
        hook = null;
    }
"""),
    ("        new ManualAssociationUiHook(", "        hook = new ManualAssociationUiHook("),
    ("        ).install();", "        );\n        hook.install();"),
])

print("Local manual source now supports the API102 retirement contract; rebuild it before bundling.")
