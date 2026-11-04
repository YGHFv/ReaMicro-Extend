#!/usr/bin/env python3
"""逐类标定「b2 逻辑类 -> b3 混淆类」的重定位证据，并可产出 overlay JSON。

用法：
  python -X utf8 tools/b3_retarget_probe.py
  python -X utf8 tools/b3_retarget_probe.py --want-file <需要的逻辑类清单>
  python -X utf8 tools/b3_retarget_probe.py --accept org.epub.ui.ContentKt=q3e \
      --emit "$env:TEMP\\reamicro-analysis\\b3-overlay.json"

为什么需要它：宿主 hook 失败清单里的类，多数在 b3 profile 里「类名已映射、成员没重定位」，
少数是「类名就没映射到」。要补类名映射就必须有可复核的证据，所以这里把
b2 源指纹、b1 真名参照（未混淆构建）和 b3 目标指纹放在一起打分：

  - code(b2->b3)：整类代码指纹一致率（同形状 + opcode 几乎一致 + 指令量级一致）
  - stringJac：字符串常量集合重叠（常量被内联搬移时会掉到 0）
  - instrRatio：指令总量比（判断是不是同一个量级的类）
  - oracle(b1->b3)：真名构建指向谁（独立第二来源，指向别人就必须否决）

接受规则与 retarget_profile.tier_decision 完全共用，避免「探针说可以、生成器说不行」。
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CACHE = Path(
    os.environ.get("REAMICRO_ANALYSIS", Path(os.environ.get("TEMP", "/tmp")) / "reamicro-analysis")
)
SOURCE_PROFILE = ROOT / "app/src/main/assets/host-profiles/2.3.3-b2.profile"
TARGET_PROFILE = ROOT / "app/src/main/assets/host-profiles/2.3.3-b3.profile"

# 运行期 hook 安装失败清单里出现过的逻辑类（含关联/阅读链路上已知要补的）。
WANT = [
    "androidx.compose.foundation.layout.SizeKt",
    "androidx.compose.foundation.layout.PaddingKt",
    "androidx.compose.foundation.layout.RowScope",
    "androidx.compose.foundation.layout.SpacerKt",
    "androidx.compose.foundation.ClickableKt",
    "androidx.compose.foundation.ImageKt",
    "androidx.compose.material3.TextKt",
    "androidx.compose.material3.IconKt",
    "androidx.lifecycle.ViewModel",
    "kotlin.coroutines.EmptyCoroutineContext",
    "kotlin.coroutines.Continuation",
    "org.epub.ui.EpubSelectionController",
    "org.epub.ui.ContentKt",
    "app.zhendong.reamicro.ui.reader.components.ScrollPagerKt",
    "app.zhendong.reamicro.ui.reader.components.EpubContainerKt",
    "app.zhendong.reamicro.ui.reader.components.ReaderBottomBarKt",
    "app.zhendong.reamicro.ui.reader.compose.ReaderHighlightScreenKt",
    "app.zhendong.reamicro.ui.home.BookshelfScreenKt",
    "app.zhendong.reamicro.ui.book.BookDetailsViewModel",
    "app.zhendong.reamicro.ui.shrine.components.TravelingMerchantSheetKt",
    "app.zhendong.reamicro.ui.profile.components.CommunityKt",
    "reamicro.composeapp.generated.resources.String0_commonMainKt",
    "app.zhendong.reamicro.arch.icons.colored.BookKt",
]


def load_retarget(module_path: Path | None = None):
    """按路径加载 retarget_profile（文件名带下划线，无法直接 import）。"""
    path = module_path or (ROOT / "tools" / "retarget_profile.py")
    spec = importlib.util.spec_from_file_location("retarget_profile", path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def evidence_for(rp, logical, source_actual, source, target, indexes, oracle, oracle_enabled=True):
    """单个逻辑类的候选证据；返回 dict 或 None（源特征缺失时）。

    注意两个名字空间：源（b2）特征按**混淆名**索引，真名参照（b1）按**逻辑名**索引。
    """
    source_feature = source.get(source_actual)
    if source_feature is None:
        return None
    oracle_feature = oracle.get(logical) if (oracle and oracle_enabled) else None
    by_shape, by_strings, by_member_shape = indexes
    candidates, via_oracle_shape = rp.candidate_pool(
        source_feature, oracle_feature, by_shape, by_strings, by_member_shape
    )
    if not candidates:
        return {
            "logical": logical,
            "source": source_actual,
            "pool": 0,
            "shape": source_feature.shape[0],
            "methods": len(source_feature.code),
            "strings": len(source_feature.strings),
            "verdict": "no-candidate",
        }
    ranked = sorted(
        (
            (
                rp.code_agreement(source_feature, target[name]),
                rp.jaccard(source_feature.strings, target[name].strings),
                name,
            )
            for name in candidates
        ),
        reverse=True,
    )
    best_code, best_strings, best = ranked[0]
    runner_code, runner_strings, runner = ranked[1] if len(ranked) > 1 else (-1.0, 0.0, "")
    instr_ratio = rp.scale_ratio(
        source_feature.instruction_count, target[best].instruction_count
    )
    oracle_target = ""
    oracle_code = -1.0
    oracle_top: list[tuple[str, float]] = []
    if oracle_feature is not None:
        oracle_ranked = sorted(
            (
                (rp.code_agreement(oracle_feature, target[name]), name)
                for name in candidates
            ),
            reverse=True,
        )
        oracle_code, oracle_target = oracle_ranked[0]
        oracle_top = [(name, round(code, 3)) for code, name in oracle_ranked[:3]]
    return {
        "logical": logical,
        "source": source_actual,
        "target": best,
        "code": round(best_code, 3),
        "stringJaccard": round(best_strings, 3),
        "instrRatio": round(instr_ratio, 3),
        "runnerUp": runner,
        "runnerCode": round(runner_code, 3),
        "runnerStringJaccard": round(runner_strings, 3),
        "pool": len(candidates),
        "viaOracleShape": via_oracle_shape,
        "oracleTarget": oracle_target,
        "oracleCode": round(oracle_code, 3),
        "oracleTop": oracle_top,
        "oracleAgrees": oracle_target == best and oracle_code >= 0.5,
        "stringJaccardOracle": round(
            rp.jaccard(oracle_feature.strings, target[best].strings), 3
        ) if oracle_feature is not None else -1.0,
        "shapeEqual": source_feature.shape == target[best].shape,
        "sourceMethods": len(source_feature.code),
        "sourceStrings": len(source_feature.strings),
        "oracleMethods": len(oracle_feature.code) if oracle_feature is not None else 0,
        "_source_feature": source_feature,
        "_oracle_feature": oracle_feature,
        "_candidates": candidates,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cache", type=Path, default=DEFAULT_CACHE)
    parser.add_argument("--source-profile", type=Path, default=SOURCE_PROFILE)
    parser.add_argument("--target-profile", type=Path, default=TARGET_PROFILE)
    parser.add_argument("--want-file", type=Path,
                        help="需要的逻辑类清单（每行一个，或用 JSON 数组）")
    parser.add_argument("--all", action="store_true", help="扫描源 profile 的全部逻辑类")
    parser.add_argument("--accept", action="append", default=[],
                        help="人工确认映射 logical=target（可重复），会记入 overlay")
    parser.add_argument("--accept-reason", default="人工复核（探针证据不足，按 b1 参照定名）")
    parser.add_argument("--emit", type=Path, help="输出 overlay JSON")
    parser.add_argument("--no-fallback-b1", dest="fallback_b1", action="store_false",
                        help="禁用「源 profile 没登记时改用 b1 真名特征」的回退")
    parser.set_defaults(fallback_b1=True)
    parser.add_argument("--quiet-pending", action="store_true")
    args = parser.parse_args()

    rp = load_retarget()
    source_profile = rp.parse_profile(args.source_profile)
    target_profile = rp.parse_profile(args.target_profile)
    source = rp.load_features(args.cache / "b2-features.pkl.gz")
    target = rp.load_features(args.cache / "b3-features.pkl.gz")
    oracle_file = args.cache / "b1-features.pkl.gz"
    oracle = rp.load_features(oracle_file) if oracle_file.is_file() else None
    indexes = rp.build_indexes(target)

    if args.want_file is not None:
        text = args.want_file.read_text(encoding="utf-8")
        if text.lstrip().startswith("["):
            want = json.loads(text)
        else:
            want = [line.strip() for line in text.splitlines() if line.strip()]
    elif args.all:
        want = sorted(source_profile["classes"])
    else:
        want = WANT
    # 逻辑类 -> b2 混淆类的真实来源以 profile 为准，避免清单写错版本。
    source_of = {}
    for logical in want:
        actual = source_profile["classes"].get(logical) or source_profile["typeAliases"].get(logical)
        if actual:
            source_of[logical] = actual

    manual = {}
    for item in args.accept:
        logical, _, actual = item.rpartition("=")
        if not logical or not actual:
            raise SystemExit(f"bad --accept: {item}")
        manual[logical] = actual
    for logical in manual:
        source_of.setdefault(logical, source_profile["classes"].get(logical, ""))
    # 人工确认的类即使不在清单里也要参与评估并写进 overlay。
    want = list(want) + [logical for logical in manual if logical not in want]

    print(f"cache={args.cache}")
    print(f"oracle={'b1-features' if oracle else 'none'}")
    print(f"target-classes={len(target)} source-classes={len(source_profile['classes'])}")
    print(
        f"{'logical':<62} {'b2':<5} {'b3':<5} {'code':>5} {'run':>5} {'strJ':>5} "
        f"{'instr':>5} {'pool':>5} {'shape':>5} {'b1':<5} {'b1c':>5} {'tier':<7}"
    )

    accepted: dict[str, dict] = {}
    pending: dict[str, dict] = {}
    unknown: list[dict] = []
    for logical in want:
        actual = source_of.get(logical, "")
        feature_source = source
        use_oracle = True
        # 源 profile 没登记（b2 时代也没映射过）时，退到未混淆构建的真名特征：
        # 只有结构/IPA 类能靠折叠形状命中，所以这类结果一律当「待人工确认」。
        if not actual and args.fallback_b1 and oracle is not None and logical in oracle:
            actual = logical
            feature_source = oracle
            use_oracle = False
        if not actual:
            unknown.append({"logical": logical, "reason": "not-in-source-profile"})
            continue
        item = evidence_for(
            rp, logical, actual, feature_source, target, indexes, oracle if use_oracle else None
        )
        if item is None:
            unknown.append({"logical": logical, "reason": "source-feature-missing", "source": actual})
            continue
        item["logical"] = logical
        item["source"] = actual
        item["sourceKind"] = "b1-fallback" if not use_oracle else "b2-profile"
        if item.get("verdict") == "no-candidate":
            unknown.append({"logical": logical, "reason": "no-candidate", "source": actual,
                            "methods": item["methods"], "strings": item["strings"]})
            print(f"{logical:<62} {actual:<5} {'-':<5} {'-':>5} {'-':>5} {'-':>5} {'-':>5} "
                  f"{0:>5} {'-':>5} {'-':<5} {'-':>5} {'pool0':<7}")
            continue
        evidence = rp.tier_decision(
            item["_source_feature"], target, item["_candidates"], oracle_feature=item["_oracle_feature"]
        )
        tier = f"tier-{evidence.rule}" if evidence is not None else "pending"
        if logical in manual:
            item["target"] = manual[logical]
            item["oracleAgrees"] = item.get("oracleTarget") == manual[logical]
            tier = "manual"
        item["rule"] = tier
        print(
            f"{logical:<62} {actual:<5} {item['target']:<5} {item['code']:>5.2f} "
            f"{item['runnerCode']:>5.2f} {item['stringJaccard']:>5.2f} {item['instrRatio']:>5.2f} "
            f"{item['pool']:>5} {('=' if item['shapeEqual'] else 'x'):>5} "
            f"{(item['oracleTarget'] or '-'):<5} {item['oracleCode']:>5.2f} {tier:<7}"
        )
        if evidence is not None or tier == "manual":
            accepted[logical] = {
                key: value for key, value in item.items() if not key.startswith("_")
            }
            if tier == "manual":
                accepted[logical]["manual"] = True
                accepted[logical]["reason"] = args.accept_reason
        else:
            pending[logical] = {
                key: value for key, value in item.items() if not key.startswith("_")
            }

    print()
    print(f"accepted={len(accepted)} pending={len(pending)} unknown={len(unknown)}")
    for logical, item in sorted(accepted.items()):
        print(
            f"  ACCEPT {logical} -> {item['target']} "
            f"code={item['code']} runner={item['runnerCode']} strJ={item['stringJaccard']} "
            f"pool={item['pool']} b1={item['oracleTarget'] or '-'}"
            + (" (manual)" if item.get("manual") else "")
        )
    if pending and not args.quiet_pending:
        print("待人工判断（未达接受门槛）：")
        for logical, item in sorted(pending.items()):
            print(
                f"  pending {logical} -> {item['target']} "
                f"code={item['code']} runner={item['runnerCode']} strJ={item['stringJaccard']} "
                f"instr={item['instrRatio']} pool={item['pool']} b1={item['oracleTarget'] or '-'} "
                f"b1code={item['oracleCode']} b1agree={item['oracleAgrees']} "
                f"b1top={item['oracleTop']}"
            )
    for item in unknown:
        print(f"  unknown {item['logical']} reason={item['reason']}")

    if args.emit is not None:
        target_id = next(
            (line.split("=", 1)[1] for line in target_profile["header"]
             if line.startswith("profile=")),
            "unknown",
        )
        payload = {
            "profileId": target_id,
            "sourceProfileId": "2.3.3-b2",
            "accepted": accepted,
            "pending": pending,
            "unknown": unknown,
        }
        args.emit.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        print(f"emit={args.emit} accepted={len(accepted)}")


if __name__ == "__main__":
    main()
