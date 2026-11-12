"""Experiment with minority-class weighting on the existing training split.

The independent test split is never passed to train() or used to select a model.
The production weight is never overwritten. Compare candidates on validation,
then run evaluate_hard_hat.py once for the chosen final weight.
"""

from __future__ import annotations

import argparse
from pathlib import Path

from ultralytics import YOLO


ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    parser = argparse.ArgumentParser(description="Fine-tune hard-hat model with class weighting")
    parser.add_argument("--data", type=Path, default=ROOT.parent / "datasets" / "hard-hat-workers-ready" / "data.yaml")
    parser.add_argument("--source", type=Path, default=ROOT / "vision-ai" / "models" / "hard-hat-best.pt")
    parser.add_argument("--epochs", type=int, default=30)
    parser.add_argument("--batch", type=int, default=4)
    parser.add_argument("--device", default="0")
    parser.add_argument("--class-weight-power", type=float, default=0.5)
    parser.add_argument("--name", default="hard-hat-class-weighted")
    args = parser.parse_args()
    if args.epochs < 1 or args.batch < 1 or not 0 <= args.class_weight_power <= 1:
        parser.error("epochs and batch must be positive; class-weight-power must be between 0 and 1")
    data = args.data.resolve(strict=True)
    source = args.source.resolve(strict=True)
    model = YOLO(str(source))
    result = model.train(
        data=str(data), epochs=args.epochs, imgsz=640, batch=args.batch,
        device=args.device, workers=0, patience=10, optimizer="SGD", lr0=0.001,
        cls_pw=args.class_weight_power, project=str(ROOT / "vision-ai" / "runs"),
        name=args.name, exist_ok=False, plots=True,
    )
    best = Path(result.save_dir) / "weights" / "best.pt"
    if not best.is_file():
        raise RuntimeError("Training ended without best.pt")
    print(f"Candidate weight: {best}")
    print("Select by validation only, then evaluate the chosen candidate on the untouched test split.")


if __name__ == "__main__":
    main()
