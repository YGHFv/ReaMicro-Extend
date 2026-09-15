from pathlib import Path
import zipfile


def build_module() -> Path:
    root = Path(__file__).resolve().parents[1]
    source = root / "ksu" / "reamicro-automation"
    output = root / "outputs" / "ReaMicro-Automation-KSU-0.1.0.zip"
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(source.iterdir()):
            if not path.is_file():
                continue
            data = path.read_text(encoding="utf-8").replace("\r\n", "\n").encode("utf-8")
            info = zipfile.ZipInfo(path.name)
            info.create_system = 3
            info.external_attr = (0o100755 if path.suffix == ".sh" else 0o100644) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data)
    return output


if __name__ == "__main__":
    print(build_module())
