#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
给所有 .kt 源文件加 GPL-3.0 文件头。

GPL 的完整用法要求每个源文件顶部都有版权与许可声明——只有一个 LICENSE 文件
在法律上是不够充分的（虽然多数情况下也不致命）。这个脚本干这件脏活。

用法：
    # 先看会改哪些文件，不实际写入
    python scripts/add_license_headers.py --author "趁满满" --year 2026 --dry-run

    # 确认无误后执行
    python scripts/add_license_headers.py --author "趁满满" --year 2026

    # 加完之后务必跑一遍测试，确认没破坏任何文件
    ./gradlew test

注意：
  - 已经带有 "GNU General Public License" 字样的文件会被跳过，重复运行安全
  - 建议在一个干净的工作区运行，跑完用 `git diff --stat` 复核
  - 单独 commit，不要和功能改动混在一起，否则 diff 没法看
"""

import argparse
import pathlib
import sys

HEADER_TEMPLATE = """/*
 * Copyright (C) {year} {author}
 *
 * This file is part of LocalVault ({project}).
 *
 * LocalVault is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LocalVault is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LocalVault.  If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
"""

MARKER = "GNU General Public License"

# 不处理的目录
SKIP_DIRS = {"build", ".gradle", ".git", ".idea", ".kotlin"}


def iter_sources(root: pathlib.Path, suffixes):
    for path in root.rglob("*"):
        if not path.is_file() or path.suffix not in suffixes:
            continue
        if any(part in SKIP_DIRS for part in path.parts):
            continue
        yield path


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--author", required=True, help="版权署名（笔名也可以）")
    ap.add_argument("--year", default="2026")
    ap.add_argument("--project", default="本地保险库")
    ap.add_argument("--root", default="app/src", help="源码根目录")
    ap.add_argument("--ext", default=".kt", help="逗号分隔的扩展名，如 .kt,.java")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    root = pathlib.Path(args.root)
    if not root.exists():
        print(f"找不到目录：{root}（请在工程根目录下运行）", file=sys.stderr)
        return 1

    suffixes = {s if s.startswith(".") else "." + s for s in args.ext.split(",")}
    header = HEADER_TEMPLATE.format(
        year=args.year, author=args.author, project=args.project
    )

    added = skipped = 0
    for path in sorted(iter_sources(root, suffixes)):
        text = path.read_text(encoding="utf-8")
        if MARKER in text[:2000]:
            skipped += 1
            continue
        if args.dry_run:
            print(f"[would add] {path}")
        else:
            # 保留原文件开头可能存在的 shebang 或 @file: 注解之前的空白处理：
            # Kotlin 里 package 之前可以有注释，直接前置即可。
            path.write_text(header + "\n" + text.lstrip("\ufeff"), encoding="utf-8")
            print(f"[added] {path}")
        added += 1

    print(f"\n完成：新增 {added} 个，跳过（已有）{skipped} 个。")
    if not args.dry_run and added:
        print("下一步：./gradlew test && git diff --stat")
    return 0


if __name__ == "__main__":
    sys.exit(main())
