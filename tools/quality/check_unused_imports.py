#!/usr/bin/env python3
"""未用 import 检查——把「IDE 里那批 The import X is never used」变成 CI 门禁。

为什么需要它：javac **不报**未用 import（那是 IDE/ECL 的检查），Maven 构建全绿也照旧留存。
结果是仓库里静默积累——本仓库在 2026-09-21/22 连清两轮（17 处 / 13 文件 + 1 文件），
若无门禁还会继续积累。

判据（贴近 JDT，宁漏不误删）：
    某个 import 的**简单名**在该文件「**代码 + javadoc**」里一次都不出现 ⇒ 未用。
    - **普通注释不算引用**（JDT 也不认）。这是修过一次的地方：曾把注释计为使用，
      于是 `RbacWiringTest` 的 `import ...WebhookProperties;`（正文只在 `// ...` 里被提到）
      被漏报——**门禁漏报比没有门禁更危险**（它给人「已经干净」的错觉）。
    - **javadoc 算引用**：`{@link Foo}` 需要该 import 才能解析，删了会断链。
    - 字符串字面量**算**引用（保守：宁可漏报，也不误删）。

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
    "tgg-escrow",
    "tgg-app",
]

# `import a.b.C;` / `import static a.b.C.method;`（`\\s*` 容忍缩进；Java 不允许跨行 import）
IMPORT_RE = re.compile(r"^\s*import\s+(?:static\s+)?([\w.]+)\s*;\s*$")

# 构建产物目录不参与检查
SKIP_DIR_NAMES = {"target", "node_modules", ".git"}


def strip_plain_comments(source: str) -> str:
    """去掉**普通**注释（`//` 与 `/* */`），保留 javadoc（`/** */`）与字符串字面量。

    为什么手写扫描而不用正则：`//` 可能出现在字符串里（如 URL），
    用正则删「到行尾」会把同一行后面**真实的**代码一并删掉，
    从而把「已使用的 import」误判为未用（接着被删 → 编译失败）。
    """
    out = []
    i = 0
    n = len(source)
    while i < n:
        ch = source[i]
        # 字符串 / 字符字面量：原样保留（含转义）
        if ch in ("'", '"'):
            quote = ch
            out.append(ch)
            i += 1
            while i < n:
                out.append(source[i])
                if source[i] == "\\":
                    i += 1
                    if i < n:
                        out.append(source[i])
                        i += 1
                    continue
                if source[i] == quote:
                    i += 1
                    break
                i += 1
            continue
        # 行注释：整段丢弃（换行留给下一轮）
        if ch == "/" and i + 1 < n and source[i + 1] == "/":
            while i < n and source[i] != "\n":
                i += 1
            continue
        # 块注释：javadoc 保留，其余丢弃
        if ch == "/" and i + 1 < n and source[i + 1] == "*":
            is_javadoc = i + 2 < n and source[i + 2] == "*"
            end = source.find("*/", i + 2)
            end = n if end == -1 else end + 2
            if is_javadoc:
                out.append(source[i:end])
            i = end
            continue
        out.append(ch)
        i += 1
    return "".join(out)


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
    # 去掉普通注释后再判定（「代码 + javadoc + 字符串」里的出现才算引用）
    body = strip_plain_comments(
        "\n".join(line for i, line in enumerate(lines) if i not in import_indexes)
    )

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
            "判据：import 的简单名在「代码 + javadoc + 字符串」里零出现（普通注释不算引用）。\n"
            "处理：删掉这些 import，并同步清理正文里对它的普通注释提及（如需保留说明，改写为 javadoc {@link}）。",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
