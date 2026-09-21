#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""依赖漏洞扫描：读 CycloneDX SBOM，查 OSV 已知漏洞。

背景
----
本仓库由 `mvn package` 生成聚合 SBOM（`target/bom.json`，见根 `pom.xml` 的
`cyclonedx-maven-plugin`）。本脚本把该 SBOM 里的每个第三方组件送交
OSV（https://osv.dev，Google 维护的开源漏洞库，聚合 GitHub Advisory / OSS-Fuzz
及各大生态公告），产出可读报告，并给出 CI 可用的退出码。

用法
----
    python3 tools/security/scan_dependencies.py \\
        --sbom target/bom.json \\
        --json-out target/security/dependency-scan.json \\
        --md-out   target/security/dependency-scan.md

退出码
------
    0  扫描完成，无达到阈值的发现
    1  扫描完成，存在达到阈值（默认 HIGH）的发现——CI 应失败
    2  用法或本地 IO 错误（SBOM 缺失/损坏等）
    3  网络或 OSV 服务错误（**不得**解读为「无漏洞」）

设计约束（来自 docs/LESSONS.md 的教训）
--------------------------------------
  * 负向结论必须换独立探针交叉验证：网络失败以退出码 3 显式失败，
    **绝不**退化成一份「0 漏洞」的报告。
  * 观测粒度不浅于待证命题：报告保留每个漏洞的原始 id / 别名 / CVSS 原文，
    并标出可用的修复版本，而不是只给一个计数。
"""
from __future__ import annotations

import argparse
import datetime
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

OSV_BATCH_URL = "https://api.osv.dev/v1/querybatch"
OSV_VULN_URL = "https://api.osv.dev/v1/vulns/"
ECOSYSTEM = "Maven"
OWN_GROUP = "com.tg.heyisheng.bot"
USER_AGENT = "tgg-dependency-scan/1.0 (+https://osv.dev)"

# 定性严重度的排序（数值越大越严重）
SEVERITY_RANK = {"UNKNOWN": 0, "LOW": 1, "MEDIUM": 2, "HIGH": 3, "CRITICAL": 4}
# OSV/GitHub 用 MODERATE，本项目统一为 MEDIUM
_SEVERITY_ALIASES = {"MODERATE": "MEDIUM", "IMPORTANT": "HIGH", "MODERATE_": "MEDIUM"}


# --------------------------------------------------------------------------- #
# 纯逻辑（可单测，见 test_scan_dependencies.py）
# --------------------------------------------------------------------------- #
def normalize_severity(raw):
    """把各样式的严重度字符串归一为 CRITICAL/HIGH/MEDIUM/LOW/UNKNOWN。"""
    if raw is None:
        return "UNKNOWN"
    text = str(raw).strip().upper()
    text = _SEVERITY_ALIASES.get(text, text)
    return text if text in SEVERITY_RANK else "UNKNOWN"


def cvss_score_to_level(score):
    """把 CVSS 基础分（数值或纯数字字符串）映射为定性等级。

    向量型分数（形如 ``CVSS:3.1/AV:N/...``）本脚本不解析——返回 UNKNOWN，
    由调用方保留原文，避免用猜测填充分级。
    """
    try:
        value = float(score)
    except (TypeError, ValueError):
        return "UNKNOWN"
    if value >= 9.0:
        return "CRITICAL"
    if value >= 7.0:
        return "HIGH"
    if value >= 4.0:
        return "MEDIUM"
    if value > 0.0:
        return "LOW"
    return "UNKNOWN"


def severity_of(vuln):
    """从 OSV vuln 记录提取定性严重度。

    优先 ``database_specific.severity``（GitHub Advisory 的来源），
    其次 ``severity[]`` 中的数值型 CVSS 分数。都取不到则 UNKNOWN。
    """
    database_specific = vuln.get("database_specific") or {}
    level = normalize_severity(database_specific.get("severity"))
    if level != "UNKNOWN":
        return level
    for entry in vuln.get("severity") or []:
        level = cvss_score_to_level(entry.get("score"))
        if level != "UNKNOWN":
            return level
    return "UNKNOWN"


def is_third_party(component):
    """自有模块不可能出现在公开漏洞库，跳过以免污染报告。"""
    group = component.get("group") or ""
    name = component.get("name") or ""
    return group != OWN_GROUP and not name.startswith("tgg-")


def coordinate(component):
    """组件的可读坐标 group:name@version。"""
    group = component.get("group") or ""
    name = component.get("name") or ""
    version = component.get("version") or "?"
    coord = "{0}:{1}".format(group, name) if group else name
    return "{0}@{1}".format(coord, version)


def osv_package_name(component):
    """OSV 的 Maven 包名约定是 groupId:artifactId。"""
    group = component.get("group") or ""
    name = component.get("name") or ""
    return "{0}:{1}".format(group, name) if group else name


def extract_components(sbom):
    """从 SBOM 取出去重后的第三方组件列表（要求有 version）。"""
    seen = set()
    result = []
    for component in sbom.get("components") or []:
        if not component.get("version"):
            continue
        if not is_third_party(component):
            continue
        key = (osv_package_name(component), component.get("version"))
        if key in seen:
            continue
        seen.add(key)
        result.append(component)
    return result


def fixed_versions(vuln):
    """从 affected[].ranges[].events[] 提取修复版本（去重、保序）。"""
    fixed = []
    for affected in vuln.get("affected") or []:
        for rng in affected.get("ranges") or []:
            for event in rng.get("events") or []:
                version = event.get("fixed")
                if version and version not in fixed:
                    fixed.append(version)
    return fixed


def cvss_vector(vuln):
    """返回首个 CVSS 原文（向量或分数），供报告展示。"""
    for entry in vuln.get("severity") or []:
        score = entry.get("score")
        if score:
            return str(score)
    return None


def summarize(vulns):
    """统计严重度分布。"""
    counts = {level: 0 for level in SEVERITY_RANK}
    for vuln in vulns:
        counts[severity_of(vuln)] = counts.get(severity_of(vuln), 0) + 1
    return counts


def worst_level(vulns):
    """返回这批漏洞里最高的严重度。"""
    worst = "UNKNOWN"
    for vuln in vulns:
        level = severity_of(vuln)
        if SEVERITY_RANK[level] > SEVERITY_RANK[worst]:
            worst = level
    return worst


# --------------------------------------------------------------------------- #
# 网络
# --------------------------------------------------------------------------- #
def _request_json(url, payload=None, timeout=60):
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    headers = {"Accept": "application/json", "User-Agent": USER_AGENT}
    if data is not None:
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=data, headers=headers)
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def scan(sbom, batch_size=1000, pause=0.1, log=print):
    """对 SBOM 执行扫描，返回报告 dict。

    网络错误直接抛出，由调用方转成退出码 3——**不**吞成空报告。
    """
    components = extract_components(sbom)
    log("[scan] 第三方组件 {0} 个；查询 OSV {1}".format(len(components), OSV_BATCH_URL))

    per_component = []   # [(component, [vuln_id, ...])]
    vuln_ids = []

    for start in range(0, len(components), batch_size):
        chunk = components[start:start + batch_size]
        payload = {"queries": [
            {"package": {"ecosystem": ECOSYSTEM, "name": osv_package_name(c)},
             "version": c.get("version")}
            for c in chunk
        ]}
        response = _request_json(OSV_BATCH_URL, payload)
        results = response.get("results") or []
        if len(results) != len(chunk):
            raise OSError(
                "OSV querybatch 返回 {0} 条结果，与请求的 {1} 个组件不符".format(
                    len(results), len(chunk)))
        for component, result in zip(chunk, results):
            ids = [v.get("id") for v in (result.get("vulns") or []) if v.get("id")]
            if ids:
                per_component.append((component, ids))
                for vid in ids:
                    if vid not in vuln_ids:
                        vuln_ids.append(vid)

    log("[scan] 命中 {0} 个组件、{1} 条漏洞；拉取详情".format(
        len(per_component), len(vuln_ids)))

    details = {}
    for index, vid in enumerate(vuln_ids, 1):
        details[vid] = _request_json(OSV_VULN_URL + urllib.parse.quote(vid, safe=""))
        if pause:
            time.sleep(pause)
    log("[scan] 详情拉取完成（{0}/{1}）".format(len(details), len(vuln_ids)))

    vulnerabilities = []
    for vid in vuln_ids:
        vuln = details[vid]
        vulnerabilities.append({
            "id": vid,
            "aliases": vuln.get("aliases") or [],
            "severity": severity_of(vuln),
            "cvss": cvss_vector(vuln),
            "summary": (vuln.get("summary") or "").strip(),
            "fixedVersions": fixed_versions(vuln),
            "url": "https://osv.dev/vulnerability/" + urllib.parse.quote(vid, safe=""),
        })
    vulnerabilities.sort(key=lambda item: (-SEVERITY_RANK[item["severity"]], item["id"]))

    component_entries = []
    for component, ids in sorted(per_component, key=lambda pair: coordinate(pair[0])):
        component_entries.append({
            "component": coordinate(component),
            "osvPackage": osv_package_name(component),
            "vulnerabilities": ids,
        })

    return {
        "generatedAt": datetime.datetime.now(datetime.timezone.utc)
                        .replace(microsecond=0).isoformat(),
        "sbomFormat": sbom.get("bomFormat"),
        "sbomSpecVersion": sbom.get("specVersion"),
        "scannedComponents": len(components),
        "vulnerableComponents": len(component_entries),
        "totalVulnerabilities": len(vulnerabilities),
        "severityCounts": summarize(details.values()) if details else
                          {level: 0 for level in SEVERITY_RANK},
        "worstSeverity": worst_level(details.values()) if details else "UNKNOWN",
        "vulnerabilities": vulnerabilities,
        "components": component_entries,
    }


# --------------------------------------------------------------------------- #
# 渲染
# --------------------------------------------------------------------------- #
def render_markdown(report, sbom_path):
    counts = report["severityCounts"]
    lines = [
        "# 依赖漏洞扫描报告（OSV）",
        "",
        "> 由 `tools/security/scan_dependencies.py` 生成；**不要手工编辑**。",
        "> 数据源：CycloneDX SBOM（cyclonedx-maven-plugin） + OSV `querybatch` / `vulns` API。",
        "",
        "- 生成时间（UTC）：`{0}`".format(report["generatedAt"]),
        "- SBOM：`{0}`（{1} {2}）".format(
            sbom_path, report["sbomFormat"] or "?", report["sbomSpecVersion"] or "?"),
        "- 扫描组件数：**{0}**".format(report["scannedComponents"]),
        "- 命中组件数：**{0}**".format(report["vulnerableComponents"]),
        "- 漏洞总数：**{0}**".format(report["totalVulnerabilities"]),
        "- 严重度分布：" + "、".join(
            "{0}={1}".format(level, counts.get(level, 0))
            for level in ("CRITICAL", "HIGH", "MEDIUM", "LOW", "UNKNOWN")),
        "- 最高严重度：**{0}**".format(report["worstSeverity"]),
        "",
    ]
    if not report["vulnerabilities"]:
        lines += ["## 结论", "",
                  "本次扫描在 OSV 中**未发现**已知漏洞命中。", "",
                  "> 注意：无命中 ≠ 无漏洞——OSV 只覆盖公开收录的漏洞，"
                  "私有/未公开漏洞与配置缺陷不在其射程内。", ""]
        return "\n".join(lines)

    lines += ["## 漏洞明细", "",
              "| 严重度 | ID | 组件 | 修复版本 | 摘要 |", "|---|---|---|---|---|"]
    component_of = {}
    for entry in report["components"]:
        for vid in entry["vulnerabilities"]:
            component_of[vid] = entry["component"]
    for vuln in report["vulnerabilities"]:
        lines.append("| {0} | [{1}]({2}) | `{3}` | {4} | {5} |".format(
            vuln["severity"], vuln["id"], vuln["url"],
            component_of.get(vuln["id"], "?"),
            ", ".join(vuln["fixedVersions"]) or "—",
            (vuln["summary"] or "—").replace("|", "\\|")[:160],
        ))
    lines.append("")
    return "\n".join(lines)


# --------------------------------------------------------------------------- #
# 入口
# --------------------------------------------------------------------------- #
def main(argv=None):
    parser = argparse.ArgumentParser(description="依赖漏洞扫描（CycloneDX SBOM → OSV）")
    parser.add_argument("--sbom", default="target/bom.json",
                        help="CycloneDX JSON SBOM 路径（默认 target/bom.json）")
    parser.add_argument("--json-out", default=None,
                        help="机器可读报告输出路径（默认 <SBOM 所在目录>/security/dependency-scan.json）")
    parser.add_argument("--md-out", default=None,
                        help="Markdown 摘要输出路径（默认 <SBOM 所在目录>/security/dependency-scan.md）")
    parser.add_argument("--fail-on", default="HIGH",
                        choices=["NONE", "LOW", "MEDIUM", "HIGH", "CRITICAL"],
                        help="达到该等级即返回退出码 1（默认 HIGH）")
    args = parser.parse_args(argv)

    try:
        with open(args.sbom, "r", encoding="utf-8") as handle:
            sbom = json.load(handle)
    except (OSError, ValueError) as exc:
        print("[ERROR] 无法读取 SBOM `{0}`：{1}".format(args.sbom, exc), file=sys.stderr)
        return 2

    try:
        report = scan(sbom)
    except urllib.error.HTTPError as exc:
        print("[ERROR] OSV 返回 HTTP {0}：{1}。本脚本 fail-closed——该结果【不能】当作「无漏洞」。".format(
            exc.code, exc.reason), file=sys.stderr)
        return 3
    except (urllib.error.URLError, OSError) as exc:
        print("[ERROR] 访问 OSV 失败：{0}。本脚本 fail-closed——该结果【不能】当作「无漏洞」。".format(exc),
              file=sys.stderr)
        return 3

    base = os.path.dirname(os.path.abspath(args.sbom))
    json_out = args.json_out or os.path.join(base, "security", "dependency-scan.json")
    md_out = args.md_out or os.path.join(base, "security", "dependency-scan.md")
    for path in (json_out, md_out):
        directory = os.path.dirname(os.path.abspath(path))
        if directory:
            os.makedirs(directory, exist_ok=True)
    with open(json_out, "w", encoding="utf-8") as handle:
        json.dump(report, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    with open(md_out, "w", encoding="utf-8") as handle:
        handle.write(render_markdown(report, args.sbom))
    print("[scan] 报告已写入：{0} / {1}".format(json_out, md_out))

    print("[scan] 严重度分布：{0}".format(
        "、".join("{0}={1}".format(k, report["severityCounts"].get(k, 0))
                  for k in ("CRITICAL", "HIGH", "MEDIUM", "LOW", "UNKNOWN"))))

    if args.fail_on == "NONE":
        return 0
    threshold = SEVERITY_RANK[args.fail_on]
    hits = [v for v in report["vulnerabilities"] if SEVERITY_RANK[v["severity"]] >= threshold]
    if hits:
        print("[scan] 达到阈值 {0} 的发现 {1} 条——退出码 1".format(args.fail_on, len(hits)),
              file=sys.stderr)
        for vuln in hits:
            print("    - {0} {1} {2}".format(vuln["severity"], vuln["id"], vuln["summary"][:80]),
                  file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
