#!/usr/bin/env python3
"""汇总 RC3 迁移边界，不输出完整源码或设备日志。"""

import json
import os
from pathlib import Path
import re

import hostsym
import retarget_profile as rp

ROOT = Path(__file__).resolve().parents[1]
CACHE = Path(os.environ["TEMP"]) / "reamicro-analysis"
OUT = CACHE / "runtime/rc3"


def main():
    rows = []
    gates = {}
    for path in (ROOT / "app/src/main/java").rglob("*"):
        if path.suffix not in (".kt", ".java"):
            continue
        text = path.read_text(encoding="utf-8")
        if "2.3.3-b4" in text:
            gates[str(path.relative_to(ROOT))] = text.count("2.3.3-b4")
        for number, line in enumerate(text.splitlines(), 1):
            if re.search(
                r'(hostClass|findClass|Class\.forName|\.name\s*==|getDeclaredMethod|'
                r'hostMethod|\.name\s+in).*"[a-z][a-z0-9]{1,4}"', line,
            ):
                rows.append([str(path.relative_to(ROOT)), number, line.strip()])
    (OUT / "code-audit.json").write_text(
        json.dumps({"gates": gates, "literals": rows}, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print("B4 gates:", sum(gates.values()), "files:", len(gates), "raw literals:", len(rows))
    for row in rows[:40]:
        print(*row)
    profile = rp.parse_profile(ROOT / "app/src/main/assets/host-profiles/2.3.3-b4.profile")
    source = rp.load_features(CACHE / "b4-features.pkl.gz")
    target = rp.load_features(CACHE / "rc3-features.pkl.gz")
    matches, unmatched = rp.match_classes(profile, source, target, .55, .20, tier=True)
    report = {"matches": [vars(item) for item in matches], "unmatched": unmatched}
    (OUT / "class-candidates.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2, default=vars), encoding="utf-8",
    )
    accepted = {item.logical: item.target for item in matches if item.confident}
    print("classes accepted:", len(accepted), "rejected:", len(matches) - len(accepted),
          "unmatched:", len(unmatched))
    for item in matches:
        if not item.confident:
            print("REJECT", item.logical, item.source, item.target)
    for item in unmatched:
        print("MISSING", item)


if __name__ == "__main__":
    main()
