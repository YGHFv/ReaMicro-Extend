#!/usr/bin/env python3
"""把旧版混淆 profile 按结构特征重定位到新构建，输出新版 profile。

背景：R8 每次构建会复用同一套短名池，但重新分配（同名类可能已经是另一个类），
所以旧 profile 的整体成员名映射在新构建上直接失效。本工具不依赖名字，只用
**改名不变量**（稳定描述符形状、字符串常量、opcode 集合、指令数）把每个逻辑类
重新定位到目标构建里的真实类，再用同样特征把成员映射回来。

  --profile         源 profile（逻辑名 -> 旧构建混淆名）
  --source-features 旧构建特征缓存（dump_features.py 输出）
  --target-features 新构建特征缓存
  --output          目标 profile 输出路径
  --report          可选 JSON 报告（逐项置信度与丢项原因）
"""

from __future__ import annotations

import argparse
import gzip
import json
import pickle
import re
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path

REFERENCE = re.compile(r"L[^;]+;")
STABLE_TYPE_PREFIXES = (
    "Ljava/",
    "Landroid/",
    "Ljavax/",
    "Ldalvik/",
    "Lkotlin/jvm/functions/Function",
)


def stable(descriptor: str) -> str:
    return REFERENCE.sub(
        lambda match: match.group(0)
        if match.group(0).startswith(STABLE_TYPE_PREFIXES)
        else "L*",
        descriptor,
    )


@dataclass
class ClassFeature:
    name: str
    shape: tuple
    strings: frozenset
    opcodes: frozenset
    instruction_count: int
    methods: list = field(default_factory=list)
    fields: list = field(default_factory=list)
    code: list = field(default_factory=list)
    parents: tuple[str, ...] = ()
    raw_methods: tuple[tuple[str, str], ...] = ()


def build_feature(entry: dict) -> ClassFeature:
    shape = (
        stable(f"L{entry.get('rawSup', '')};") if entry.get("rawSup") else "",
        tuple(sorted(stable(f"L{item};") for item in entry.get("rawIfc", ()))),
        tuple(sorted(stable(d) for _n, d in entry.get("rawMeth", ()))),
        tuple(sorted(stable(d) for _n, d in entry.get("rawFld", ()))),
    )
    strings = set()
    opcodes = set()
    total = 0
    for method in entry.get("meth", ()):
        strings.update(method.get("t", ()))
        opcodes.update(method.get("o", ()))
        total += method.get("i", 0)
    return ClassFeature(
        name="",
        shape=shape,
        strings=frozenset(strings),
        opcodes=frozenset(opcodes),
        instruction_count=total,
        methods=list(entry.get("meth", ())),
        # rawFld 是 (名, 描述符) 对；fld 只有稳定描述符，无法给出成员名。
        fields=[{"n": name, "d": descriptor} for name, descriptor in entry.get("rawFld", ())],
        code=[
            {
                "n": method.get("n", ""),
                "s": method.get("s"),
                "o": frozenset(method.get("o", ())),
                "i": method.get("i", 0),
            }
            for method in entry.get("meth", ())
        ],
        parents=tuple(
            descriptor[1:-1].replace("/", ".")
            for descriptor in (entry.get("rawSup", ""), *entry.get("rawIfc", ()))
            if descriptor.startswith("L") and descriptor.endswith(";")
        ),
        raw_methods=tuple(entry.get("rawMeth", ())),
    )


def load_features(path: Path) -> dict[str, ClassFeature]:
    with gzip.open(path, "rb") as handle:
        raw = pickle.load(handle)
    return {name: build_feature(entry) for name, entry in raw.items()}


def jaccard(left: frozenset, right: frozenset) -> float:
    if not left and not right:
        return 0.0
    return len(left & right) / len(left | right)


def opcode_agreement(left: frozenset, right: frozenset) -> float:
    """方法级 opcode 一致率。

    接口方法和抽象方法没有方法体，``meth[o]`` 是空集合。空对空表示「两边都是纯声明」，
    这正是同名同形的强证据，必须算一致；沿用 ``jaccard`` 会把它们一律判成 0，
    导致 ``kotlin.coroutines.Continuation``、``kotlin.jvm.functions.Function2`` 这类
    纯接口永远配不上（实测池内上万候选全部 0 分）。
    """
    if not left and not right:
        return 1.0
    return jaccard(left, right)


def scale_ratio(source_count: int, target_count: int) -> float:
    """指令量级比。两边都没有方法体时视为同量级。"""
    if source_count == 0 and target_count == 0:
        return 1.0
    return min(source_count, target_count) / max(1, max(source_count, target_count))


def member_similarity(source: dict, target: dict) -> float:
    """成员级相似度：稳定描述符必须一致，再看字符串/opcode/指令数。"""
    if source.get("s") != target.get("s"):
        return -1.0
    strings = jaccard(frozenset(source.get("t", ())), frozenset(target.get("t", ())))
    opcodes = opcode_agreement(
        frozenset(source.get("o", ())), frozenset(target.get("o", (())))
    )
    src_count = source.get("i", 0)
    dst_count = target.get("i", 0)
    count_score = 1.0 - min(1.0, abs(src_count - dst_count) / max(1, max(src_count, dst_count)))
    return strings * 0.6 + opcodes * 0.2 + count_score * 0.2


def parse_profile(path: Path) -> dict:
    data = {
        "header": [],
        "classes": {},
        "methods": {},
        "fields": {},
        "typeAliases": {},
        "methodOwners": {},
    }
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        matched = False
        for prefix, bucket in (
            ("class=", "classes"),
            ("typeAlias=", "typeAliases"),
            ("method=", "methods"),
            ("methodOwner=", "methodOwners"),
            ("field=", "fields"),
        ):
            if line.startswith(prefix):
                body = line[len(prefix):]
                key, _, value = body.rpartition("=")
                data[bucket][key.strip()] = value.strip()
                matched = True
                break
        if not matched:
            data["header"].append(line)
    return data


@dataclass
class ClassMatch:
    logical: str
    source: str
    target: str
    stringScore: float
    opcodeScore: float
    margin: float
    candidates: int
    confident: bool
    oracleTarget: str = ""
    oracleScore: float = 0.0
    oracleAgrees: bool = False
    viaOracleShape: bool = False
    viaTier: bool = False
    tierEvidence: "TierEvidence | None" = None


def feature_score(left: ClassFeature, right: ClassFeature) -> tuple[float, float]:
    return (
        jaccard(left.strings, right.strings),
        jaccard(left.opcodes, right.opcodes),
    )


# 整类代码指纹比对**不能**跳过构造器/静态初始化：枚举类的 <clinit> 字节量是
# 区分「同名枚举」的关键证据（b3 里 MutatePriority 就靠它从 1082 个同形候选里选出）。
SKIP_MEMBER_PREFIX = ("access$", "lambda$", "$r8$")
# 成员映射只处理普通方法：构造器/静态初始化永远不会是 profile 的目标。
SKIP_MEMBER_TARGET = ("access$", "lambda$", "$r8$", "<init>", "<clinit>")
# 真名参照（b1 oracle）否决成员映射所需的「明显更确定」差量：oracle 最佳候选必须
# 比主指纹所选高出该值，才允许把主指纹的映射丢掉，避免低分分歧顶掉完美同形结果。
ORACLE_VETO_MARGIN = 0.05


def code_agreement(
    source: ClassFeature,
    target: ClassFeature,
    opcode_threshold: float = 0.95,
    count_tolerance: float = 0.10,
) -> float:
    """整类代码指纹一致率：多少比例的源方法能在目标类里找到同形状同指令特征的方法。"""
    if not source.code:
        return 0.0
    matched = 0
    for method in source.code:
        source_opcodes = method["o"]
        for candidate in target.code:
            if candidate["n"].startswith(SKIP_MEMBER_PREFIX):
                continue
            if candidate["s"] != method["s"]:
                continue
            opcodes = opcode_agreement(source_opcodes, candidate["o"])
            ratio = scale_ratio(method["i"], candidate["i"])
            if opcodes >= opcode_threshold and ratio >= 1.0 - count_tolerance:
                matched += 1
                break
    return matched / len(source.code)


@dataclass
class TierEvidence:
    """tier 判定的可复现证据，用于报告与人工复核。"""

    rule: str
    target: str
    code: float
    string_score: float
    instr_ratio: float
    shape_equal: bool
    runner_up: str
    runner_code: float
    pool: int
    oracle_target: str = ""
    oracle_agrees: bool = False


def tier_decision(
    source_feature: ClassFeature,
    target: dict[str, ClassFeature],
    candidates: list[str],
    oracle_feature: ClassFeature | None = None,
) -> TierEvidence | None:
    """第三层接受规则：常量被内联搬移的门面类，改用代码指纹定身份。

    校准背景（见 reports/b3-retarget-selfcheck 与 b1->b2 回测）：
    - 结构形状唯一并不安全，b3 里大量 Compose 类的常量被内联搬移，
      `jaccard(strings)` 常为 0，但这些类的**整类代码指纹**仍高度一致；
    - 只靠「形状唯一」会在无关类里乱认领，所以每条规则都要求
      「代码一致率 + 指令量级 + 候选池内明显拉开」三件事同时成立。

    规则分层（rule 字段会写进报告，便于复核）：
      A   代码一致率 >= 0.90 且候选池内明显拉开（强证据）
      B   0.75 <= 代码一致率 < 0.90，且结构形状一致或字符串重叠 >= 0.40
      C   候选池唯一且代码一致率 >= 0.75（形状/常量都因内联而变形，只能靠唯一性）
      D   字符串重叠 >= 0.85 且代码一致率 >= 0.60、指令量比 >= 0.50（门面类部分内联）
      E   指令量比 >= 0.85、代码一致率 >= 0.65、字符串重叠 >= 0.40 且候选池唯一

    说明：整类「结构形状逐字段一致」（接口集合 + 全部成员描述符）看着很适合当第 6 条
    规则，实测不可靠——b1 -> b2 回测里它把 kotlin.Lazy、kotlin.jvm.functions.Function2、
    RememberObserver 都认成了别的同形类，精度掉到 0.9143。这类「没有方法体的接口」的
    重定位因此一律走人工 overlay（b3_retarget_probe.py --accept）并附证据。

    真名参照（b1）只当否决票：它在这类内联门面上并不比源指纹更准（实测
    code_agreement 普遍 < 0.2），因此只在它给出明显更强结论时否决。
    """
    if not source_feature.code:
        return None
    ranked: list[tuple[float, float, float, str]] = []
    for candidate in candidates:
        target_feature = target[candidate]
        ratio = scale_ratio(
            source_feature.instruction_count, target_feature.instruction_count
        )
        code = code_agreement(source_feature, target_feature)
        strings = jaccard(source_feature.strings, target_feature.strings)
        ranked.append((code, strings, ratio, candidate))
    ranked.sort(key=lambda item: (item[0], item[1], item[2], item[3]), reverse=True)
    best_code, best_strings, best_ratio, best = ranked[0]
    runner_code, _rs, _rr, runner = ranked[1] if len(ranked) > 1 else (-1.0, 0.0, 0.0, "")
    shape_equal = source_feature.shape == target[best].shape
    unique = len(candidates) == 1
    dominant = unique or (best_code - runner_code) >= 0.30 or runner_code <= best_code * 0.5
    rule = ""
    if best_code >= 0.90 and dominant and best_ratio >= 0.20:
        rule = "A"
    elif (
        0.75 <= best_code < 0.90
        and dominant
        and best_ratio >= 0.20
        and (shape_equal or best_strings >= 0.40)
    ):
        rule = "B"
    elif unique and best_code >= 0.75 and best_ratio >= 0.20:
        rule = "C"
    elif best_code >= 0.60 and best_ratio >= 0.50 and best_strings >= 0.85 and dominant:
        rule = "D"
    elif (
        unique
        and best_ratio >= 0.85
        and best_code >= 0.65
        and best_strings >= 0.40
    ):
        rule = "E"
    if not rule:
        return None
    oracle_target = ""
    oracle_agrees = False
    if oracle_feature is not None:
        oracle_scored = sorted(
            (code_agreement(oracle_feature, target[candidate]), candidate)
            for candidate in candidates
        )
        oracle_code, oracle_target = oracle_scored[-1]
        oracle_agrees = oracle_target == best
        # 真名构建（b1）在这里只当**否决票**：它对代码指纹更友好（无内联损失），
        # 所以它能给出强结论时必须尊重；但它自己也可能因为内联/版本差异认错，
        # 因此只有「明显优于源指纹的最佳候选」才否决。
        if (
            not oracle_agrees
            and oracle_code >= 0.5
            and oracle_code > best_code + 0.15
        ):
            return None
    return TierEvidence(
        rule=rule,
        target=best,
        code=best_code,
        string_score=best_strings,
        instr_ratio=best_ratio,
        shape_equal=shape_equal,
        runner_up=runner,
        runner_code=runner_code,
        pool=len(candidates),
        oracle_target=oracle_target,
        oracle_agrees=oracle_agrees,
    )


def build_indexes(
    target: dict[str, ClassFeature],
) -> tuple[dict, dict, dict]:
    """目标构建的三种「改名不变量」索引，供候选池构造使用。"""
    by_shape: dict[tuple, list[str]] = {}
    by_strings: dict[frozenset, list[str]] = {}
    by_member_shape: dict[tuple, list[str]] = {}
    for name, feature in target.items():
        by_shape.setdefault(feature.shape, []).append(name)
        if len(feature.strings) >= 3:
            by_strings.setdefault(feature.strings, []).append(name)
        for member_shape in {method["s"] for method in feature.code}:
            by_member_shape.setdefault(member_shape, []).append(name)
    return by_shape, by_strings, by_member_shape


def candidate_pool(
    source_feature: ClassFeature,
    oracle_feature: ClassFeature | None,
    by_shape: dict,
    by_strings: dict,
    by_member_shape: dict,
) -> tuple[list[str], bool]:
    """候选池：折叠结构形状 ∪ 字符串集合一致 ∪ 成员形状重叠 >= 60%（∪ 真名参照）。

    返回 (候选列表, 是否因真名参照形状而新增候选)。
    """
    candidate_set = set(by_shape.get(source_feature.shape, ()))
    # 字符串常量集合完全一致是最强的改名不变量：R8 不改字符串字面量，
    # 即使方法被内联，保留下来的方法仍带着同样的常量集合。
    if len(source_feature.strings) >= 3:
        candidate_set |= set(by_strings.get(source_feature.strings, ()))
    # 结构形状会因内联而变；再按「成员形状重叠度」补充候选，交给严格的
    # 接受条件去判定，避免漏掉方法被部分内联的类。
    source_shapes = {method["s"] for method in source_feature.code}
    if source_shapes:
        overlap: Counter = Counter()
        for member_shape in source_shapes:
            for name in by_member_shape.get(member_shape, ()):
                overlap[name] += 1
        threshold = max(1, round(len(source_shapes) * 0.6))
        candidate_set |= {name for name, hits in overlap.items() if hits >= threshold}
    via_oracle_shape = False
    if oracle_feature is not None:
        extra = set(by_shape.get(oracle_feature.shape, ()))
        via_oracle_shape = bool(extra - candidate_set)
        candidate_set |= extra
        if len(oracle_feature.strings) >= 3:
            candidate_set |= set(by_strings.get(oracle_feature.strings, ()))
    return sorted(candidate_set), via_oracle_shape


def match_classes(
    profile: dict,
    source: dict[str, ClassFeature],
    target: dict[str, ClassFeature],
    min_string_score: float,
    min_margin: float,
    oracle: dict[str, ClassFeature] | None = None,
    strict: bool = True,
    tier: bool = False,
) -> tuple[list[ClassMatch], list[dict]]:
    indexes = build_indexes(target)
    by_shape, by_strings, by_member_shape = indexes

    matches: list[ClassMatch] = []
    unmatched: list[dict] = []
    for logical, actual in sorted(profile["classes"].items()):
        source_feature = source.get(actual)
        if source_feature is None:
            unmatched.append({"logical": logical, "actual": actual, "reason": "source-missing"})
            continue
        oracle_feature = oracle.get(logical) if oracle else None
        candidates, via_oracle_shape = candidate_pool(
            source_feature, oracle_feature, by_shape, by_strings, by_member_shape
        )
        if not candidates:
            unmatched.append({"logical": logical, "actual": actual, "reason": "no-shape-match"})
            continue
        scored = [
            (*feature_score(source_feature, target[candidate]), candidate)
            for candidate in candidates
        ]
        scored.sort(reverse=True)
        best_string, best_opcode, best = scored[0]
        runner_string = scored[1][0] if len(scored) > 1 else -1.0
        margin = best_string - runner_string
        oracle_target = ""
        oracle_score = 0.0
        oracle_agrees = False
        if oracle_feature is not None:
            oracle_scored = sorted(
                (jaccard(oracle_feature.strings, target[candidate].strings), candidate)
                for candidate in candidates
            )
            oracle_score, oracle_target = oracle_scored[-1]
            oracle_agrees = oracle_target == best
            if not oracle_agrees:
                via_oracle_shape = False
        # 校准结果（见 reports/b3-retarget-selfcheck）：只靠「结构形状唯一」会认领到
        # 形状退化后偶然相同的无关类（b1->b2 自测里 4/13 是错的）。因此默认要求
        # 正面身份证据：整类字符串集合完全一致，且指令量量级一致；多候选还要求
        # 两个独立来源指向同一目标。
        best_feature = target[best]
        shared_strings = len(source_feature.strings & best_feature.strings)
        count_ratio = scale_ratio(
            source_feature.instruction_count, best_feature.instruction_count
        )
        unique = len(candidates) == 1
        via_tier = False
        tier_evidence: TierEvidence | None = None
        if strict:
            # 只认「整类字符串常量集合完全一致」：这是不随改名/内联变化的身份证据。
            exact_source = {
                candidate for candidate in candidates
                if source_feature.strings and
                target[candidate].strings == source_feature.strings
            }
            exact_oracle = {
                candidate for candidate in candidates
                if oracle_feature is not None and oracle_feature.strings and
                target[candidate].strings == oracle_feature.strings
            }
            if len(exact_source) == 1:
                chosen = next(iter(exact_source))
            elif oracle_feature is not None and len(exact_source & exact_oracle) == 1:
                # 两个独立来源的常量集合交集只剩一个候选，才接受。
                chosen = next(iter(exact_source & exact_oracle))
            else:
                chosen = None
            if chosen is None:
                # 常量可能因内联搬进搬出：允许高重叠（>=0.9）但要求代码指纹也一致，
                # 且这样的候选只能有一个。
                tier2 = [
                    candidate for candidate in candidates
                    if source_feature.strings
                    and jaccard(source_feature.strings, target[candidate].strings) >= 0.9
                    and code_agreement(source_feature, target[candidate]) >= 0.7
                ]
                if len(tier2) == 1:
                    chosen = tier2[0]
            if chosen is None and not tier:
                # 两层（strict）基线的兜底：无字符串常量的库类按整类代码指纹认领。
                # 这条路径只凭「代码一致率 + 拉开幅度」判定，不检查指令量级；开启
                # --tier 后改由 tier_decision 统一接管，下面那层会额外要求指令量级，
                # 并顺带处理「字符串常量被 R8 内联搬空」的门面类。
                ranked = sorted(
                    (
                        (code_agreement(source_feature, target[candidate]), candidate)
                        for candidate in candidates
                    ),
                    reverse=True,
                )
                if ranked and ranked[0][0] >= 0.9:
                    top = ranked[0][0]
                    runner = ranked[1][0] if len(ranked) > 1 else -1.0
                    if top - runner >= 0.1 or len(candidates) == 1:
                        chosen = ranked[0][1]
            if chosen is None and tier:
                # 第三层：常量被内联搬移的 Compose/库门面类，只能靠代码指纹定身份。
                tier_evidence = tier_decision(
                    source_feature, target, candidates, oracle_feature=oracle_feature
                )
                if tier_evidence is not None:
                    chosen = tier_evidence.target
                    via_tier = True
            if chosen is not None and chosen != best:
                best = chosen
                best_feature = target[best]
                best_string, best_opcode, _ = next(
                    item for item in scored if item[2] == best
                )
            if via_tier:
                # tier 路径本来就是为「没有字符串常量」准备的，不能再要求常量数量。
                confident = True
            else:
                confident = (
                    chosen is not None
                    and len(source_feature.strings) >= 3
                    and count_ratio >= 0.6
                )
        else:
            has_signal = best_string > 0.0 or best_opcode > 0.0
            if unique:
                confident = True
            elif oracle_feature is not None:
                confident = oracle_agrees and has_signal and (
                    best_string >= min_string_score or margin >= min_margin
                )
            else:
                confident = best_string >= min_string_score and margin >= min_margin
        if oracle_feature is not None:
            oracle_agrees = oracle_target == best
            if not oracle_agrees:
                via_oracle_shape = False
        matches.append(
            ClassMatch(
                logical=logical,
                source=actual,
                target=best,
                stringScore=best_string,
                opcodeScore=best_opcode,
                margin=margin,
                candidates=len(candidates),
                confident=confident,
                oracleTarget=oracle_target,
                oracleScore=oracle_score,
                oracleAgrees=oracle_agrees,
                viaOracleShape=via_oracle_shape,
                viaTier=via_tier,
                tierEvidence=tier_evidence,
            ),
        )
    return matches, unmatched


def match_members(
    profile: dict,
    source: dict[str, ClassFeature],
    target: dict[str, ClassFeature],
    class_map: dict[str, str],
    oracle: dict[str, ClassFeature] | None = None,
) -> tuple[dict, dict, dict, dict]:
    methods: dict[str, str] = {}
    fields: dict[str, str] = {}
    dropped: dict[str, list] = {"methods": [], "fields": []}
    used_method_names: set[tuple[str, str]] = set()

    source_owner = {}
    for logical, actual in profile["classes"].items():
        source_owner.setdefault(logical, actual)
    for logical, actual in profile["typeAliases"].items():
        source_owner.setdefault(logical, actual)

    def oracle_member(feature: ClassFeature | None, logical_name: str) -> dict | None:
        if feature is None:
            return None
        for member in feature.methods:
            if member["n"] == logical_name:
                return member
        base = logical_name.split("-")[0]
        hits = [m for m in feature.methods if m["n"].split("-")[0] == base]
        return hits[0] if len(hits) == 1 else None

    def source_members(class_name: str, member: str) -> dict | None:
        feature = source.get(class_name)
        if feature is None:
            return None
        return next((m for m in feature.methods if m["n"] == member), None)

    for key, actual_member in sorted(profile["methods"].items()):
        logical_class, _, logical_name = key.partition("#")
        target_class = class_map.get(logical_class)
        source_class = profile["methodOwners"].get(key) or source_owner.get(logical_class)
        if target_class is None or source_class is None:
            dropped["methods"].append({"key": key, "reason": "class-unmapped"})
            continue
        source_feature = source.get(source_class)
        target_feature = target.get(target_class)
        if source_feature is None or target_feature is None:
            dropped["methods"].append({"key": key, "reason": "feature-missing"})
            continue
        source_method = next(
            (m for m in source_feature.methods if m["n"] == actual_member),
            None,
        )
        if source_method is None:
            dropped["methods"].append({"key": key, "reason": "source-member-missing"})
            continue
        scored = []
        for candidate in target_feature.methods:
            if candidate["n"].startswith(SKIP_MEMBER_TARGET):
                continue
            score = member_similarity(source_method, candidate)
            if score < 0:
                continue
            scored.append((score, candidate["n"]))
        if not scored:
            dropped["methods"].append({"key": key, "reason": "no-candidate"})
            continue
        scored.sort(reverse=True)
        best_score, best_name = scored[0]
        runner = scored[1][0] if len(scored) > 1 else -1.0
        if oracle is not None:
            oracle_method = oracle_member(oracle.get(logical_class), logical_name)
            if oracle_method is not None:
                oracle_scored = sorted(
                    (member_similarity(oracle_method, candidate), candidate["n"])
                    for candidate in target_feature.methods
                    if not candidate["n"].startswith(SKIP_MEMBER_TARGET)
                    and member_similarity(oracle_method, candidate) >= 0
                )
                # 真名参照（b1）与当前构建差异很大，它的意见只在「自己也很确定」时才
                # 有否决力：低分的分歧（例如把构造器当成最近邻）不该丢掉正确的映射。
                # 另外 oracle 还必须比主指纹「明显更确定」才允许否决——否则一个 0.95 的
                # 非最佳候选会顶掉 1.00 的完美同形映射（第 54 轮实测 3 处 veto 全部如此）。
                oracle_best_score, oracle_best_name = oracle_scored[-1] if oracle_scored else (-1.0, "")
                if (
                    oracle_best_score >= 0.5
                    and oracle_best_score > best_score + ORACLE_VETO_MARGIN
                    and oracle_best_name != best_name
                ):
                    dropped["methods"].append(
                        {"key": key, "reason": "oracle-disagrees",
                         "picked": best_name, "oracle": oracle_best_name,
                         "pickedScore": round(best_score, 3),
                         "oracleScore": round(oracle_best_score, 3)},
                    )
                    continue
        if len(scored) > 1 and best_score - runner < 0.10:
            # 指纹拉不开时，用「成员名跨构建稳定」这一实测先验消歧：实测 212 个
            # 已映射类里 205 个的成员名集合完全一致，19 处不一致的类由指纹层挑出。
            # 这里只在已通过「稳定形状一致」筛选的候选里选同名者，且量级必须接近。
            same_name = [
                (score, name) for score, name in scored if name == actual_member
            ]
            same_counts = (
                next((m["i"] for m in target_feature.methods if m["n"] == actual_member), 0)
            )
            ratio = min(source_method.get("i", 0), same_counts) / max(
                1, max(source_method.get("i", 0), same_counts)
            )
            if len(same_name) == 1 and ratio >= 0.6:
                best_name = same_name[0][1]
                best_score = same_name[0][0]
            else:
                dropped["methods"].append(
                    {"key": key, "reason": "ambiguous", "score": best_score,
                     "runner": runner, "source": actual_member}
                )
                continue
        key_in_use = (target_class, best_name)
        if key_in_use in used_method_names:
            dropped["methods"].append({"key": key, "reason": "duplicate-target", "score": best_score})
            continue
        used_method_names.add(key_in_use)
        methods[key] = best_name

    used_field_names: set[tuple[str, str]] = set()

    def logical_source_methods(logical_class: str) -> dict[str, str]:
        return {
            value: key
            for key, value in profile["methods"].items()
            if key.startswith(f"{logical_class}#")
        }

    def logical_target_methods(logical_class: str) -> dict[str, str]:
        return {
            value: key
            for key, value in methods.items()
            if key.startswith(f"{logical_class}#")
        }

    def field_writers(feature: ClassFeature) -> dict[str, set[str]]:
        index = {member["n"]: set() for member in feature.fields}
        for method in feature.methods:
            for written in method.get("w", ()):
                if written in index:
                    index[written].add(method["n"])
        return index

    for key, actual_member in sorted(profile["fields"].items()):
        logical_class, _, _logical_name = key.partition("#")
        target_class = class_map.get(logical_class)
        source_class = source_owner.get(logical_class)
        if target_class is None or source_class is None:
            dropped["fields"].append({"key": key, "reason": "class-unmapped"})
            continue
        source_feature = source.get(source_class)
        target_feature = target.get(target_class)
        if source_feature is None or target_feature is None:
            dropped["fields"].append({"key": key, "reason": "feature-missing"})
            continue
        source_field = next(
            (f for f in source_feature.fields if f["n"] == actual_member),
            None,
        )
        if source_field is None:
            dropped["fields"].append({"key": key, "reason": "source-member-missing"})
            continue
        want = stable(source_field["d"])
        candidates = [f for f in target_feature.fields if stable(f["d"]) == want]
        if not candidates:
            dropped["fields"].append({"key": key, "reason": "no-candidate"})
            continue
        if len(candidates) == 1:
            best_name = candidates[0]["n"]
        else:
            # 描述符拉不开时，用「谁写这个字段」的调用图消歧。
            source_index = field_writers(source_feature)
            target_index = field_writers(target_feature)
            source_map = logical_source_methods(logical_class)
            target_map = logical_target_methods(logical_class)
            want_writers = {
                source_map[name]
                for name in source_index.get(actual_member, set())
                if name in source_map
            }
            resolved = None
            for candidate in candidates:
                got = {
                    target_map[name]
                    for name in target_index.get(candidate["n"], set())
                    if name in target_map
                }
                if want_writers and got == want_writers:
                    if resolved is None:
                        resolved = candidate["n"]
                    else:
                        resolved = None
                        break
            if resolved is None:
                # 同方法：描述符拉不开时，优先「同名」候选（成员名跨构建高度稳定，
                # 例如 org.epub.ui.BodyKt#LocalEpubMarks 在 b2/b3 都叫 c）。
                same_name = [f for f in candidates if f["n"] == actual_member]
                if len(same_name) == 1:
                    resolved = same_name[0]["n"]
                else:
                    dropped["fields"].append(
                        {"key": key, "reason": "ambiguous", "candidates": len(candidates)}
                    )
                    continue
            best_name = resolved
        if (target_class, best_name) in used_field_names:
            dropped["fields"].append({"key": key, "reason": "duplicate-target"})
            continue
        used_field_names.add((target_class, best_name))
        fields[key] = best_name
    return methods, fields, dropped, {}


def overlay_target(item: str | dict) -> str:
    value = item.get("target") if isinstance(item, dict) else item
    if not isinstance(value, str) or not value:
        raise ValueError(f"overlay 目标必须是非空字符串：{item!r}")
    return value


def overlay_classes(payload: dict) -> dict:
    if "accepted" in payload:
        return payload["accepted"]
    reserved = {"fields", "methods", "methodOwners", "typeAliases", "pending", "rejected",
                "profileId", "sourceProfileId", "note"}
    return {key: value for key, value in payload.items() if key not in reserved}


def apply_member_overlays(
    profile: dict,
    target: dict[str, ClassFeature],
    class_map: dict[str, str],
    type_aliases: dict[str, str],
    methods: dict[str, str],
    fields: dict[str, str],
    method_owners: dict[str, str],
    overlays: list[tuple[Path, dict]],
    oracle: dict[str, ClassFeature] | None = None,
) -> list[dict]:
    """先原子合并，再校验最终双向唯一性；允许同一批 overlay 交换字段名。"""
    merged = {bucket: dict(values) for bucket, values in (
        ("methods", methods), ("fields", fields), ("methodOwners", method_owners),
        ("typeAliases", type_aliases),
    )}
    entries = []

    def known_class(logical: str) -> bool:
        return logical in profile["classes"] or logical in profile["typeAliases"] or (
            oracle is not None and logical in oracle
        )

    for path, payload in overlays:
        for bucket in merged:
            values = payload.get(bucket, {})
            if not isinstance(values, dict):
                raise ValueError(f"{path}: {bucket} 必须是对象")
            for key, item in sorted(values.items()):
                logical, separator, member = key.partition("#")
                if not known_class(logical) or (bucket != "typeAliases" and
                                                (not separator or not member or "#" in member)):
                    raise ValueError(f"{path}: 未知逻辑类或非法成员键 {bucket}/{key}")
                actual = overlay_target(item)
                if bucket == "typeAliases" and logical in class_map:
                    raise ValueError(f"{path}: typeAlias 与 class 重复定义 {logical}")
                entries.append({"path": str(path), "kind": bucket, "key": key,
                                "previous": merged[bucket].get(key), "target": actual,
                                "evidence": item if isinstance(item, dict) else {}})
                merged[bucket][key] = actual

    owners = class_map | merged["typeAliases"]

    def hierarchy(actual: str) -> list[ClassFeature]:
        pending, seen, result = [actual], set(), []
        while pending:
            name = pending.pop(0)
            if name in seen or name not in target:
                continue
            seen.add(name)
            feature = target[name]
            result.append(feature)
            pending.extend(feature.parents)
        return result

    for key, actual in merged["typeAliases"].items():
        if actual not in target:
            raise ValueError(f"overlay typeAlias 目标不存在：{key} -> {actual}")
    for key, actual in merged["methodOwners"].items():
        if key not in merged["methods"] or actual not in target:
            raise ValueError(f"overlay methodOwner 悬空：{key} -> {actual}")
    for bucket in ("methods", "fields"):
        claimed = {}
        for key, actual in merged[bucket].items():
            logical, _, _ = key.partition("#")
            owner = merged["methodOwners"].get(key) if bucket == "methods" else None
            owner = owner or owners.get(logical)
            if not owner:
                raise ValueError(f"overlay 成员所属类未映射：{key}")
            if bucket == "methods" and actual in ("<init>", "<clinit>"):
                raise ValueError(f"overlay 不允许映射构造器：{key}")
            # 以真正的声明类校验冲突，避免子类与父类别名绕过唯一性。
            declarations = [(feature, item) for feature in hierarchy(owner)
                            for item in getattr(feature, bucket) if item["n"] == actual]
            if not declarations:
                raise ValueError(f"overlay 成员不存在：{key} -> {owner}#{actual}")
            declaration = declarations[0][0]
            identity = (id(declaration), actual)
            if identity in claimed:
                raise ValueError(f"overlay 成员目标冲突：{key} / {claimed[identity]} -> {owner}#{actual}")
            claimed[identity] = key
    for index, entry in enumerate(entries):
        descriptor = entry["evidence"].get("descriptor")
        if not descriptor or entry["kind"] not in ("methods", "fields"):
            continue
        key, bucket = entry["key"], entry["kind"]
        # 后置 overlay 可以覆盖前一条，只检查最终生效的证据。
        if any(later["kind"] == bucket and later["key"] == key
               for later in entries[index + 1:]):
            continue
        logical = key.partition("#")[0]
        owner = merged["methodOwners"].get(key) if bucket == "methods" else None
        owner = owner or owners[logical]
        declarations = [item for feature in hierarchy(owner) for item in getattr(feature, bucket)
                        if item["n"] == merged[bucket][key]]
        if bucket == "methods":
            matches_descriptor = any(
                name == merged[bucket][key] and "".join(raw.split()) == "".join(descriptor.split())
                for feature in hierarchy(owner) for name, raw in feature.raw_methods
            )
        else:
            matches_descriptor = any(item["d"] == descriptor for item in declarations)
        if not matches_descriptor:
            raise ValueError(f"overlay 目标描述符不匹配：{key} -> {descriptor}")
    for bucket, original in (("methods", methods), ("fields", fields),
                             ("methodOwners", method_owners), ("typeAliases", type_aliases)):
        original.clear()
        original.update(merged[bucket])
    return entries


def render_profile(
    source_header: list[str],
    profile_id: str,
    size_hint: int,
    classes: dict[str, str],
    type_aliases: dict[str, str],
    methods: dict[str, str],
    fields: dict[str, str],
    method_owners: dict[str, str],
) -> str:
    lines = [f"profile={profile_id}"]
    package_line = next((line for line in source_header if line.startswith("package=")), None)
    lines.append(package_line or "package=app.zhendong.reamicro")
    for line in source_header:
        if line.startswith(("package=", "profile=")):
            continue
        if line.startswith("sizeHint=") and size_hint:
            lines.append(f"sizeHint={size_hint}")
            continue
        lines.append(line)
    if size_hint and not any(line.startswith("sizeHint=") for line in lines):
        lines.append(f"sizeHint={size_hint}")
    lines += [f"class={key}={value}" for key, value in sorted(classes.items())]
    lines += [f"typeAlias={key}={value}" for key, value in sorted(type_aliases.items())]
    lines += [f"methodOwner={key}={value}" for key, value in sorted(method_owners.items())]
    lines += [f"method={key}={value}" for key, value in sorted(methods.items())]
    lines += [f"field={key}={value}" for key, value in sorted(fields.items())]
    return "\n".join(lines) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--profile", type=Path, required=True)
    parser.add_argument("--source-features", type=Path, required=True)
    parser.add_argument("--target-features", type=Path, required=True)
    parser.add_argument("--oracle-features", type=Path,
                        help="第二个独立参照特征（真名构建），用于交叉验证")
    parser.add_argument("--profile-id", required=True)
    parser.add_argument("--size-hint", type=int, default=0)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--min-string-score", type=float, default=0.55)
    parser.add_argument("--min-margin", type=float, default=0.20)
    parser.add_argument("--tier", action="store_true",
                        help="启用第三层接受规则（代码指纹定身份，用于常量被内联的 Compose/库类）")
    parser.add_argument("--overlay", type=Path, action="append",
                        help="类/成员映射 JSON，可重复；支持 accepted、typeAliases、methods、fields、methodOwners")
    args = parser.parse_args()

    profile = parse_profile(args.profile)
    source = load_features(args.source_features)
    target = load_features(args.target_features)
    oracle = load_features(args.oracle_features) if args.oracle_features else None

    matches, unmatched = match_classes(
        profile,
        source,
        target,
        args.min_string_score,
        args.min_margin,
        oracle,
        tier=args.tier,
    )
    class_map = {item.logical: item.target for item in matches if item.confident}
    rejected = [
        {"logical": item.logical, "source": item.source, "target": item.target,
         "stringScore": round(item.stringScore, 3), "margin": round(item.margin, 3),
         "candidates": item.candidates, "oracleTarget": item.oracleTarget,
         "oracleScore": round(item.oracleScore, 3), "oracleAgrees": item.oracleAgrees}
        for item in matches
        if not item.confident
    ]
    target_use: dict[str, list[str]] = {}
    for item in matches:
        if item.confident:
            target_use.setdefault(item.target, []).append(item.logical)
    conflicts = {target: logics for target, logics in target_use.items() if len(logics) > 1}

    # --overlay：把探针（b3_retarget_probe.py）逐条标定过的映射并入。
    # 每条都要在这里重新校验，避免探针产物被手改后把 profile 指向不存在的类。
    overlay_entries: list[dict] = []
    overlay_skipped: list[dict] = []
    overlays = []
    for path in (args.overlay or []):
        payload = json.loads(path.read_text(encoding="utf-8"))
        overlays.append((path, payload))
    for path, payload in overlays:
        accepted = overlay_classes(payload)
        claimed = {value: key for key, value in class_map.items()}
        for logical, item in sorted(accepted.items()):
            actual_target = overlay_target(item)
            replace = isinstance(item, dict) and item.get("replace") is True
            if logical in class_map and not replace:
                overlay_skipped.append({"logical": logical, "reason": "already-mapped",
                                        "target": class_map[logical]})
                continue
            if actual_target not in target:
                overlay_skipped.append({"logical": logical, "reason": "target-missing",
                                        "target": actual_target})
                continue
            if actual_target in claimed and claimed[actual_target] != logical:
                overlay_skipped.append({"logical": logical, "reason": "target-taken",
                                        "target": actual_target, "by": claimed[actual_target]})
                continue
            source_actual = profile["classes"].get(logical) or profile["typeAliases"].get(logical)
            if source_actual is None and oracle is not None and logical in oracle:
                source_actual = logical
            if source_actual is None:
                overlay_skipped.append({"logical": logical, "reason": "logical-unknown"})
                continue
            previous = class_map.get(logical)
            if previous is not None and previous != actual_target:
                claimed.pop(previous, None)
            class_map[logical] = actual_target
            claimed[actual_target] = logical
            target_use.setdefault(actual_target, []).append(logical)
            overlay_entries.append({
                "logical": logical,
                "source": source_actual,
                "target": actual_target,
                "evidence": item if isinstance(item, dict) else {},
            })
        print(f"overlay={path} entries={len(accepted)}")

    methods, fields, dropped, _ = match_members(profile, source, target, class_map, oracle)

    # typeAlias：同一逻辑名的备用实现名，只有唯一高分目标才保留。
    alias_errors: list[dict] = []
    type_aliases: dict[str, str] = {}
    for logical, actual in sorted(profile["typeAliases"].items()):
        # 同一逻辑名只保留一条别名：profile 解析是按 key 覆盖的，多写也会被丢掉。
        if logical in class_map or logical in type_aliases:
            continue
        source_feature = source.get(actual)
        if source_feature is None:
            alias_errors.append({"logical": logical, "actual": actual, "reason": "source-missing"})
            continue
        candidates = [
            name for name, feature in target.items() if feature.shape == source_feature.shape
        ]
        if len(candidates) != 1:
            alias_errors.append({"logical": logical, "actual": actual, "reason": "not-unique",
                                 "candidates": len(candidates)})
            continue
        type_aliases[logical] = candidates[0]

    method_owners = {
        key: class_map[key.partition("#")[0]]
        for key in profile["methodOwners"]
        if key.partition("#")[0] in class_map
    }

    member_overlay_entries = apply_member_overlays(
        profile, target, class_map, type_aliases, methods, fields, method_owners, overlays, oracle,
    )
    for bucket in ("methods", "fields"):
        applied = {item["key"] for item in member_overlay_entries if item["kind"] == bucket}
        dropped[bucket] = [item for item in dropped[bucket] if item["key"] not in applied]
    target_use = {}
    for logical, actual in class_map.items():
        target_use.setdefault(actual, []).append(logical)
    conflicts = {actual: logics for actual, logics in target_use.items() if len(logics) > 1}
    if conflicts:
        raise ValueError(f"overlay 类目标冲突：{conflicts}")

    rendered = render_profile(
        profile["header"],
        args.profile_id,
        args.size_hint,
        class_map,
        type_aliases,
        methods,
        fields,
        method_owners,
    )
    # host-profiles 资产在 Windows 上以 CRLF 提交，显式落盘才能与仓库资产逐字节一致
    # （render_profile 只产出 "\n"，write_bytes 不再做换行翻译）。
    rendered = rendered.replace("\n", "\r\n")
    args.output.write_bytes(rendered.encode("utf-8"))

    summary = {
        "profileId": args.profile_id,
        "sourceClasses": len(profile["classes"]),
        "sourceMethods": len(profile["methods"]),
        "sourceFields": len(profile["fields"]),
        "sourceTypeAliases": len(profile["typeAliases"]),
        "matchedClasses": len(class_map),
        "matchedMethods": len(methods),
        "matchedFields": len(fields),
        "matchedTypeAliases": len(type_aliases),
        "oracleAgreedClasses": sum(1 for item in matches if item.oracleAgrees),
        "oracleDisagreedClasses": sum(
            1 for item in matches if item.oracleTarget and not item.oracleAgrees
        ),
        "bijectionConflicts": len(conflicts),
        "tierClasses": sum(1 for item in matches if item.viaTier),
        "overlayClasses": len(overlay_entries),
        "overlaySkipped": len(overlay_skipped),
        "overlayMembers": len(member_overlay_entries),
        "unmatchedClasses": len(unmatched),
        "rejectedClasses": len(rejected),
        "droppedMethods": Counter(item["reason"] for item in dropped["methods"]),
        "droppedFields": Counter(item["reason"] for item in dropped["fields"]),
        "output": str(args.output),
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2, default=dict))
    if args.report is not None:
        args.report.write_text(
            json.dumps(
                {
                    "summary": {k: (dict(v) if isinstance(v, Counter) else v) for k, v in summary.items()},
                    "unmatchedClasses": unmatched,
                    "rejectedClasses": rejected,
                    "conflicts": conflicts,
                    "matches": [
                        {
                            "logical": item.logical,
                            "source": item.source,
                            "target": item.target,
                            "stringScore": round(item.stringScore, 3),
                            "opcodeScore": round(item.opcodeScore, 3),
                            "margin": round(item.margin, 3),
                            "candidates": item.candidates,
                            "confident": item.confident,
                            "viaTier": item.viaTier,
                            "oracleTarget": item.oracleTarget,
                            "oracleAgrees": item.oracleAgrees,
                        }
                        for item in matches
                    ],
                    "droppedMethods": dropped["methods"],
                    "droppedFields": dropped["fields"],
                    "aliasErrors": alias_errors,
                    "tierClasses": [
                        {
                            "logical": item.logical,
                            "source": item.source,
                            "target": item.target,
                            "evidence": item.tierEvidence.__dict__ if item.tierEvidence else None,
                        }
                        for item in matches
                        if item.viaTier
                    ],
                    "overlayClasses": overlay_entries,
                    "overlaySkipped": overlay_skipped,
                    "overlayMembers": member_overlay_entries,
                },
                ensure_ascii=False,
                indent=2,
            ),
            encoding="utf-8",
        )
        print(f"report={args.report}")


if __name__ == "__main__":
    main()
