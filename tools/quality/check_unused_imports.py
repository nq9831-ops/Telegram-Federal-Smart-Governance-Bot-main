#!/usr/bin/env python3
"""未用 import 检查——把「IDE 里那批 The import X is never used」变成 CI 门禁。

为什么需要它：javac **不报**未用 import（那是 IDE/ECL 的检查），Maven 构建全绿也照旧留存。
结果是仓库里静默积累——本仓库在 2026-09-21/22 连清两轮（17 处 / 13 文件 + 1 文件），
若无门禁还会继续积累。

判据（保守，宁漏不误删）：
    某个 import 的**简单名**在该文件「除 import 行以外的全部文本」里**一次都不出现** ⇒ 未用。
    注释与 javadoc 也算正文——因此仅供 `{@link Foo}` 引用的 import **不会被误报**。

用法：
    python3 tools/quality/check_unused_imports.py            # 全仓检查
    python3 tools/quality/check_unused_imports.py tgg-core   # 只查指定模块/目录

退出码：
    0 = 无未用 import
    1 = 有（即 CI 应失败）
    2 = 参数/IO 错误
"""

from __future__ import annotations

import os
import re
import sys

# 默认检查的模块（本仓库的 Maven 模块根）。刻意写死而非动态发现：
# 新增模块时必须显式加进来——门禁的覆盖面应当是可审计的，不该随目录变化而悄悄漂移。
DEFAULT_ROOTS = [
    "tgg-common",
    "tgg-core",
    "tgg-credit",
    "tgg-federation",
    "tgg-listing",
    "tgg-admin",
    "tgg-app",
]

# `import a.b.C;` / `import static a.b.C.method;`（`\s*` 容忍缩进；不做多行处理，Java 不允许）
IMPORT_RE = re.compile(r"^\s*import\s+(?:static\s+)?([\w.]+)\s*;\s*$")

# 构建产物目录不参与检查
SKIP_DIR_NAMES = {"target", "node_modules", ".git"}


def java_files(root: str):
    """产出 root 下所有 .java（跳过构建产物目录）。"""
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIR_NAMES]
        for name in filenames:
            if name.endswith(".java"):
                yield os.path.join(dirpath, name)


def unused_imports(path: str):
    """返回该文件的未用 import 列表：[(行号, 原始行文本)]。"""
    with open(path, encoding="utf-8") as handle:
        lines = handle.read().split("\n")

    imports = []  # (行下标, 简单名)
    for index, line in enumerate(lines):
        match = IMPORT_RE.match(line)
        if match:
            imports.append((index, match.group(1).split(".")[-1]))

    if not imports:
        return []

    import_indexes = {index for index, _ in imports}
    body = "\n".join(line for i, line in enumerate(lines) if i not in import_indexes)

    findings = []
    for index, simple in imports:
        if simple == "*":
            # 通配 import 无法静态判定（其贡献的名字不在本文件文本里）——跳过，不误报
            continue
        if not re.search(r"\b" + re.escape(simple) + r"\b", body):
            findings.append((index + 1, lines[index].strip()))
    return findings


def main(argv) -> int:
    if len(argv) > 1 and argv[1] in ("-h", "--help"):
        print(__doc__)
        return 0

    roots = argv[1:] or DEFAULT_ROOTS
    for root in roots:
        if not os.path.isdir(root):
            print(f"check_unused_imports: 目录不存在：{root}", file=sys.stderr)
            return 2

    total = 0
    scanned = 0
    for root in roots:
        for path in java_files(root):
            scanned += 1
            for line_no, line in unused_imports(path):
                print(f"{path}:{line_no}: {line}")
                total += 1

    print(f"check_unused_imports: 扫描 {scanned} 个 .java，未用 import {total} 处")
    if total:
        print(
            "判据：import 的简单名在该文件（除 import 行外的全部文本）中零出现。\n"
            "处理：删除这些 import；若它们仅供 javadoc 的 {@link} 引用则不会出现在本报告里。",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
