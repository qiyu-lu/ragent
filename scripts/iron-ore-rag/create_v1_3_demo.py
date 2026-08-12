#!/usr/bin/env python3
"""Create the controlled V1.3-demo workbook without re-saving it through Excel/POI.

Only three cells are changed. The OOXML package, drawings, media, styles and all
other worksheet XML are copied byte-for-byte so this demo artifact cannot
silently accumulate unrelated formatting changes.
"""

from __future__ import annotations

import argparse
import html
import os
import posixpath
import re
import sys
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET


SHEET_NAME = "浓度检测（双场景）"
CHANGES = {
    "F17": (
        "1.现场取样（采集矿浆至采样桶，无固定采样量，确保样品具有代表性，避免杂质混入）；",
        "1.现场取样（采集矿浆至采样桶，无固定采样量，确保样品具有代表性，避免杂质混入；"
        "演示要求：记录采样桶编号、样品编号、采样时间和操作人）；【V1.3-demo 演示变更】",
    ),
    "F19": (
        "3.烘干处理（将盛有矿浆的采样桶放至电热板（或智能恒温鼓风干燥箱），烘干至矿浆完全干燥）；"
        "105±5℃（行业通用标准）；",
        "3.烘干处理（将盛有矿浆的采样桶放至电热板（或智能恒温鼓风干燥箱），烘干至矿浆完全干燥）；"
        "105±3℃（演示质量判据）；【V1.3-demo 演示变更】",
    ),
    "F20": (
        "4.称干重（将烘干后的采样桶置于冷却装置冷却至室温，称取干矿样 + 采样桶总干重）；",
        "4.称干重（使用坩埚钳取出烘干后的采样桶，置于冷却装置冷却至室温，再称取干矿样 + 采样桶总干重；"
        "发现容器破损、样品洒落或称量异常时停止并报告）；【V1.3-demo 演示变更】",
    ),
}

MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
PACKAGE_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
NS = f"{{{MAIN_NS}}}"


def parse_args() -> argparse.Namespace:
    repo_root = Path(__file__).resolve().parents[2]
    source_dir = repo_root / "local-data" / "source"
    parser = argparse.ArgumentParser(description="生成三处受控变更的 V1.3-demo XLSX")
    parser.add_argument(
        "input",
        nargs="?",
        type=Path,
        default=source_dir / "铁矿石人工检测流程调研V1.2.xlsx",
    )
    parser.add_argument(
        "output",
        nargs="?",
        type=Path,
        default=source_dir / "铁矿石人工检测流程调研V1.3-demo.xlsx",
    )
    parser.add_argument("--force", action="store_true", help="覆盖已存在的输出文件")
    parser.add_argument("--dry-run", action="store_true", help="只校验输入和打印三处变更")
    return parser.parse_args()


def worksheet_path(archive: zipfile.ZipFile) -> str:
    workbook = ET.fromstring(archive.read("xl/workbook.xml"))
    relationship_id = None
    for sheet in workbook.findall(f"{NS}sheets/{NS}sheet"):
        if sheet.get("name") == SHEET_NAME:
            relationship_id = sheet.get(f"{{{REL_NS}}}id")
            break
    if not relationship_id:
        raise ValueError(f"未找到工作表：{SHEET_NAME}")

    relationships = ET.fromstring(archive.read("xl/_rels/workbook.xml.rels"))
    for relationship in relationships.findall(f"{{{PACKAGE_REL_NS}}}Relationship"):
        if relationship.get("Id") == relationship_id:
            target = relationship.get("Target")
            if not target:
                break
            return posixpath.normpath(posixpath.join("xl", target.lstrip("/")))
    raise ValueError(f"未找到工作表关系：{relationship_id}")


def shared_strings(archive: zipfile.ZipFile) -> list[str]:
    root = ET.fromstring(archive.read("xl/sharedStrings.xml"))
    return ["".join(node.text or "" for node in item.iter(f"{NS}t")) for item in root]


def cell_value(cell: ET.Element, strings: list[str]) -> str:
    cell_type = cell.get("t")
    if cell_type == "inlineStr":
        return "".join(node.text or "" for node in cell.iter(f"{NS}t"))
    value = cell.find(f"{NS}v")
    raw = "" if value is None or value.text is None else value.text
    if cell_type == "s":
        return strings[int(raw)]
    return raw


def validate_source(archive: zipfile.ZipFile, sheet_path: str) -> None:
    root = ET.fromstring(archive.read(sheet_path))
    strings = shared_strings(archive)
    for address, (expected, _) in CHANGES.items():
        cell = root.find(f".//{NS}c[@r='{address}']")
        if cell is None:
            raise ValueError(f"{SHEET_NAME}!{address} 不存在")
        if cell.find(f"{NS}f") is not None:
            raise ValueError(f"{SHEET_NAME}!{address} 是公式单元格，拒绝覆盖")
        actual = cell_value(cell, strings)
        if actual != expected:
            raise ValueError(
                f"{SHEET_NAME}!{address} 输入内容不符合 V1.2 前置条件\n"
                f"期望：{expected}\n实际：{actual}"
            )


def replace_cell_xml(worksheet_xml: bytes) -> bytes:
    text = worksheet_xml.decode("utf-8")
    for address, (_, replacement) in CHANGES.items():
        pattern = re.compile(
            rf"(<c\b(?=[^>]*\br=\"{re.escape(address)}\")[^>]*>)(.*?)(</c>)",
            re.DOTALL,
        )
        match = pattern.search(text)
        if not match:
            raise ValueError(f"无法定位 {SHEET_NAME}!{address} 的 XML")
        opening = re.sub(r'\s+t="[^"]*"', "", match.group(1))
        opening = opening[:-1] + ' t="inlineStr">'
        escaped = html.escape(replacement, quote=False)
        cell_xml = f'{opening}<is><t xml:space="preserve">{escaped}</t></is>{match.group(3)}'
        text = text[: match.start()] + cell_xml + text[match.end() :]
    return text.encode("utf-8")


def verify_output(source: Path, output: Path, sheet_path: str) -> None:
    with zipfile.ZipFile(source) as before, zipfile.ZipFile(output) as after:
        if before.namelist() != after.namelist():
            raise ValueError("输出 XLSX 包内文件清单发生变化")
        broken = after.testzip()
        if broken:
            raise ValueError(f"输出 XLSX ZIP 校验失败：{broken}")
        root = ET.fromstring(after.read(sheet_path))
        strings = shared_strings(after)
        for address, (_, expected) in CHANGES.items():
            cell = root.find(f".//{NS}c[@r='{address}']")
            if cell is None or cell_value(cell, strings) != expected:
                raise ValueError(f"输出复核失败：{SHEET_NAME}!{address}")


def create_demo(source: Path, output: Path, force: bool, dry_run: bool) -> None:
    source = source.resolve()
    output = output.resolve()
    if not source.is_file():
        raise ValueError(f"输入文件不存在：{source}")
    if source == output:
        raise ValueError("输出不能覆盖 V1.2 输入文件")
    if output.exists() and not force and not dry_run:
        raise ValueError(f"输出已存在；如需重建请加 --force：{output}")

    with zipfile.ZipFile(source) as archive:
        sheet_path = worksheet_path(archive)
        validate_source(archive, sheet_path)
        print(f"已校验输入：{source}")
        for address, (before, after) in CHANGES.items():
            print(f"- {SHEET_NAME}!{address}\n  - {before}\n  + {after}")
        if dry_run:
            return

        replacement = replace_cell_xml(archive.read(sheet_path))
        output.parent.mkdir(parents=True, exist_ok=True)
        temporary = output.with_name(output.name + ".tmp")
        try:
            with zipfile.ZipFile(temporary, "w", allowZip64=True) as target:
                target.comment = archive.comment
                for info in archive.infolist():
                    target.writestr(info, replacement if info.filename == sheet_path else archive.read(info.filename))
            os.replace(temporary, output)
        finally:
            if temporary.exists():
                temporary.unlink()

    verify_output(source, output, sheet_path)
    print(f"已生成：{output}")
    print("说明：该文件是明确标注的演示数据，只包含以上三处单元格变化。")


def main() -> int:
    args = parse_args()
    try:
        create_demo(args.input, args.output, args.force, args.dry_run)
        return 0
    except (OSError, ValueError, zipfile.BadZipFile) as exc:
        print(f"错误：{exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
