"""Evaluate the frozen hard-hat model on the independent test split.

Usage: .venv/Scripts/python evaluate_hard_hat.py --device 0
The script never trains or modifies model weights or dataset labels.
"""

from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
import platform
from datetime import datetime, timezone
from pathlib import Path

import torch
import ultralytics
from ultralytics import YOLO


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DATA = ROOT.parent / "datasets" / "hard-hat-workers-ready" / "data.yaml"
DEFAULT_MODEL = ROOT / "vision-ai" / "models" / "hard-hat-best.pt"


def file_hash(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def json_number(value: object) -> int | float | str:
    if hasattr(value, "item"):
        return value.item()
    return str(value)


def main() -> None:
    parser = argparse.ArgumentParser(description="Independent hard-hat YOLO test evaluation")
    parser.add_argument("--data", type=Path, default=DEFAULT_DATA)
    parser.add_argument("--model", type=Path, default=DEFAULT_MODEL)
    parser.add_argument("--device", default="cpu")
    parser.add_argument("--batch", type=int, default=8)
    parser.add_argument("--out", type=Path, default=ROOT / "docs" / "evidence" / "hard_hat_test.json")
    args = parser.parse_args()
    data = args.data.resolve(strict=True)
    model_path = args.model.resolve(strict=True)
    dataset_root = data.parent
    splits = {}
    for name in ("train", "val", "test"):
        images = dataset_root / name / "images"
        labels = dataset_root / name / "labels"
        if not images.is_dir() or not labels.is_dir():
            raise FileNotFoundError(f"Missing {name} images or labels: {dataset_root}")
        label_files = list(labels.glob("*.txt"))
        class_counts = Counter()
        for label_file in label_files:
            for row in label_file.read_text(encoding="utf-8").splitlines():
                if row.strip():
                    class_counts[row.split()[0]] += 1
        splits[name] = {"images": len(list(images.glob("*.*"))), "labels": len(label_files),
                        "class_instances": dict(sorted(class_counts.items()))}
    if any(item["images"] == 0 for item in splits.values()):
        raise ValueError("All three dataset splits must be nonempty")

    result = YOLO(str(model_path)).val(
        data=str(data), split="test", imgsz=640, batch=args.batch,
        device=args.device, workers=0, plots=False, save_json=False,
        project=str(ROOT / "vision-ai" / "runs"), name="taskbook-test-evaluation", exist_ok=True,
    )
    report = {
        "evaluated_at_utc": datetime.now(timezone.utc).isoformat(),
        "split": "test",
        "model_sha256": file_hash(model_path),
        "dataset_yaml_sha256": file_hash(data),
        "dataset_counts": splits,
        "image_size": 640,
        "batch": args.batch,
        "device": args.device,
        "gpu": torch.cuda.get_device_name(0) if args.device != "cpu" and torch.cuda.is_available() else None,
        "python": platform.python_version(),
        "pytorch": torch.__version__,
        "ultralytics": ultralytics.__version__,
        "precision": float(result.box.mp),
        "recall": float(result.box.mr),
        "map50": float(result.box.map50),
        "map50_95": float(result.box.map),
        "per_class": result.summary(),
        "taskbook_map50_target": 0.80,
        "taskbook_map50_passed": float(result.box.map50) >= 0.80,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2, default=json_number), encoding="utf-8")
    print(json.dumps({"map50": report["map50"], "target_passed": report["taskbook_map50_passed"],
                      "report": str(args.out.resolve())}, ensure_ascii=False))


if __name__ == "__main__":
    main()
