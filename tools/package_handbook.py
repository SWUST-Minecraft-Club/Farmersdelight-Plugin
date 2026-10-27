"""Validate and package the player handbook for HandbookCore; never changes a server's files."""
from __future__ import annotations

import argparse
from collections import Counter
from hashlib import sha256
from pathlib import Path
import re
import zipfile

import yaml

from check_duplicate_yaml_keys import compose_file, find_duplicates

ROOT = Path(__file__).resolve().parent.parent
CONTENT = ROOT / "docs/handbook/content/farmersdelight.yml"
VERSION = "1.0.0"
ID = re.compile(r"farmersdelight:[a-z0-9_./-]+\Z")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def validate() -> dict:
    node, error = compose_file(CONTENT)
    require(error is None, str(error))
    duplicates: list = []
    find_duplicates(node, set(), duplicates, Counter())
    require(not duplicates, f"Duplicate YAML keys: {duplicates}")
    book = yaml.safe_load(CONTENT.read_text(encoding="utf-8"))
    require(isinstance(book, dict), "Handbook must be a mapping")
    categories, entries = book.get("categories"), book.get("entries")
    require(isinstance(categories, list) and bool(categories), "Missing categories")
    require(isinstance(entries, list) and bool(entries), "Missing entries")
    ids: set[str] = set()
    for value in categories + entries:
        require(isinstance(value, dict), "Category and entry must be mappings")
        key = value.get("id", "")
        require(isinstance(key, str) and ID.fullmatch(key) is not None, f"Invalid ID: {key}")
        require(key not in ids, f"Duplicate ID: {key}")
        ids.add(key)
        require(isinstance(value.get("title"), str) and bool(value["title"].strip()), f"Missing title: {key}")
        require(value.get("permission", "") == "", f"Player content must be public: {key}")
    category_ids = {value["id"] for value in categories}
    entry_ids = {value["id"] for value in entries}
    known_items: set[str] = set()
    for file in (ROOT / "src/main/resources/craftengine/farmersdelight/configuration").glob("*.yml"):
        known_items.update((yaml.safe_load(file.read_text(encoding="utf-8")) or {}).get("items", {}))
    for entry in entries:
        key = entry["id"]
        require(entry.get("category") in category_ids, f"Missing category for {key}")
        require(isinstance(entry.get("summary"), str), f"Missing summary for {key}")
        require(isinstance(entry.get("keywords"), list) and all(isinstance(s, str) for s in entry["keywords"]),
                f"Invalid keywords for {key}")
        pages = entry.get("pages")
        require(isinstance(pages, list) and 1 <= len(pages) <= 128, f"Invalid pages for {key}")
        for page in pages:
            require(isinstance(page, dict) and isinstance(page.get("blocks"), list) and bool(page["blocks"]),
                    f"Empty or invalid page in {key}")
            for block in page["blocks"]:
                if isinstance(block, str):
                    require(bool(block.strip()), f"Empty text in {key}")
                    continue
                require(isinstance(block, dict), f"Invalid block in {key}")
                kind = block.get("type")
                require(kind in {"text", "item", "link", "command", "space"}, f"Unsupported block {kind} in {key}")
                if kind == "link":
                    require(block.get("entry") in entry_ids, f"Broken link in {key}: {block.get('entry')}")
                    require(bool(block.get("label")), f"Unlabelled link in {key}")
                elif kind == "command":
                    require(block.get("command") == "fd recipe", f"Only the player recipe command is allowed: {key}")
                    require(bool(block.get("label")), f"Unlabelled command in {key}")
                elif kind == "item":
                    require(block.get("ce-item") in known_items, f"Unknown CE item in {key}: {block.get('ce-item')}")
                    require(bool(block.get("caption")) and bool(block.get("material")), f"Incomplete item preview in {key}")
                elif kind == "text":
                    require(isinstance(block.get("text"), str) and bool(block["text"].strip()), f"Empty text in {key}")
                elif kind == "space":
                    require(isinstance(block.get("lines", 1), int) and 1 <= block.get("lines", 1) <= 8,
                            f"Invalid spacing in {key}")
    print(f"Handbook checked: {len(categories)} category, {len(entries)} player entries; links, item IDs and commands valid")
    return book


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="validate content without writing artifacts")
    parser.add_argument("--output-dir", type=Path, default=ROOT / "build/handbook")
    args = parser.parse_args()
    validate()
    if args.check:
        return
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)
    content = CONTENT.read_bytes()
    (output / CONTENT.name).write_bytes(content)
    archive = output / f"FarmersDelight-Handbook-{VERSION}.zip"
    files = {
        "plugins/HandbookCore/content/farmersdelight.yml": content,
        "FarmersDelight-Handbook-README.md": (ROOT / "docs/handbook/README.md").read_bytes(),
    }
    files["CONTENTS-SHA256SUMS.txt"] = "".join(
        f"{sha256(data).hexdigest()}  {name}\n" for name, data in sorted(files.items())
    ).encode("utf-8")
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as bundle:
        for name, data in sorted(files.items()):
            info = zipfile.ZipInfo(name, date_time=(2026, 10, 26, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            bundle.writestr(info, data)
    (output / "HANDBOOK-SHA256SUMS.txt").write_text("".join(
        f"{sha256(file.read_bytes()).hexdigest()}  {file.name}\n" for file in (archive, output / CONTENT.name)
    ), encoding="utf-8")
    print(f"Packaged {archive}")


if __name__ == "__main__":
    main()
