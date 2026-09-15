from pathlib import Path
import re
import zipfile


MODULE_FILES = (
    "module.prop",
    "customize.sh",
    "service.sh",
    "watchdog.sh",
    "runner.sh",
    "uninstall.sh",
)


def build_module(source: Path | None = None, output_directory: Path | None = None) -> Path:
    root = Path(__file__).resolve().parents[1]
    source = source if source is not None else root / "ksu" / "reamicro-automation"
    output_directory = output_directory if output_directory is not None else root / "outputs"
    entries = {
        name: (source / name).read_text(encoding="utf-8-sig").replace("\r\n", "\n").encode("utf-8")
        for name in MODULE_FILES
    }
    properties = {}
    for line in entries["module.prop"].decode("utf-8").splitlines():
        key, separator, value = line.partition("=")
        if separator:
            properties[key.strip()] = value.strip()
    if properties.get("id") != "reamicro_automation":
        raise ValueError("Unexpected KSU module ID")
    version = properties.get("version", "")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", version):
        raise ValueError("Missing or invalid KSU module version")
    output = output_directory / f"ReaMicro-Automation-KSU-{version}.zip"
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name)
            info.create_system = 3
            info.external_attr = (0o100755 if name.endswith(".sh") else 0o100644) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data)
    return output


if __name__ == "__main__":
    print(build_module())
