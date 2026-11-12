"""Evaluate EasyOCR against manually transcribed, independent image labels.

CSV columns: image,expected. Paths may be absolute or relative to the CSV file.
No metric is reported when the manifest is empty or contains missing labels.
"""

from __future__ import annotations

import argparse
import csv
import json
import unicodedata
from datetime import datetime, timezone
from pathlib import Path

import easyocr


def normalize(text: str) -> str:
    return "".join(c for c in unicodedata.normalize("NFKC", text).casefold() if not c.isspace())


def distance(left: str, right: str) -> int:
    previous = list(range(len(right) + 1))
    for i, char in enumerate(left, 1):
        current = [i]
        for j, other in enumerate(right, 1):
            current.append(min(current[-1] + 1, previous[j] + 1,
                               previous[j - 1] + (char != other)))
        previous = current
    return previous[-1]


def main() -> None:
    parser = argparse.ArgumentParser(description="Measure OCR character accuracy on labeled images")
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--out", type=Path, default=Path(__file__).resolve().parents[1] / "docs" / "evidence" / "ocr_test.json")
    parser.add_argument("--gpu", action="store_true")
    args = parser.parse_args()
    manifest = args.manifest.resolve(strict=True)
    with manifest.open(encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        if not {"image", "expected"}.issubset(reader.fieldnames or []):
            raise ValueError("CSV must contain image,expected columns")
        cases = list(reader)
    if not cases:
        raise ValueError("At least one labeled image is required")
    engine = easyocr.Reader(["ch_sim", "en"], gpu=args.gpu)
    rows = []
    total_characters = total_edits = 0
    for case in cases:
        image = Path(case["image"])
        if not image.is_absolute():
            image = manifest.parent / image
        image = image.resolve(strict=True)
        expected = case["expected"]
        if not expected.strip():
            raise ValueError(f"Missing manual transcription: {image}")
        actual = " ".join(engine.readtext(str(image), detail=0, paragraph=True))
        reference, prediction = normalize(expected), normalize(actual)
        edits = distance(reference, prediction)
        total_characters += len(reference)
        total_edits += edits
        rows.append({"image": str(image), "expected": expected, "actual": actual,
                     "reference_characters": len(reference), "edit_distance": edits})
    report = {"evaluated_at_utc": datetime.now(timezone.utc).isoformat(), "manifest": str(manifest),
              "images": len(rows), "normalization": "NFKC + casefold + remove whitespace",
              "total_reference_characters": total_characters, "total_edit_distance": total_edits,
              "character_accuracy": max(0.0, 1.0 - total_edits / total_characters),
              "taskbook_target": 0.90, "cases": rows}
    report["taskbook_target_passed"] = report["character_accuracy"] >= 0.90
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"character_accuracy": report["character_accuracy"],
                      "target_passed": report["taskbook_target_passed"],
                      "report": str(args.out.resolve())}, ensure_ascii=False))


if __name__ == "__main__":
    main()
