"""Measure 20 distinct students' OJ submission-to-verdict latency.

Run only against a prepared test deployment. The token file has one bearer token
per line and must stay outside Git. This script writes no token or source code
to the report. It creates real submissions and requires --confirm-run.
"""

from __future__ import annotations

import argparse
import json
import statistics
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen


def call(url: str, token: str, payload: dict | None = None) -> dict:
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    request = Request(url, data=data, headers={"Authorization": f"Bearer {token}",
                   "Content-Type": "application/json"}, method="POST" if payload else "GET")
    with urlopen(request, timeout=10) as response:
        result = json.load(response)
    if result.get("code") != 200:
        raise RuntimeError(f"API returned code {result.get('code')}: {result.get('message', '')}")
    return result.get("data") or {}


def submit_one(base: str, token: str, question_id: str, code: str, deadline: float) -> dict:
    started = time.monotonic()
    try:
        created = call(base + "/question_submit/", token,
                       {"questionId": question_id, "language": "python", "code": code})
        serial = created.get("submitNo")
        if not serial:
            raise RuntimeError("Submission response has no submitNo")
        query = urlencode({"questionId": question_id, "submitNo": serial})
        while time.monotonic() - started < deadline:
            status = call(base + "/question_submit/status/my?" + query, token)
            if status.get("status") in (2, 3):
                judge_info = status.get("judgeInfo")
                try:
                    verdict = json.loads(judge_info).get("message") if isinstance(judge_info, str) else None
                except (ValueError, TypeError):
                    verdict = None
                return {"elapsed_seconds": round(time.monotonic() - started, 3),
                        "status": status["status"], "verdict": verdict}
            time.sleep(0.5)
        return {"elapsed_seconds": round(time.monotonic() - started, 3), "error": "verdict_timeout"}
    except Exception as error:
        return {"elapsed_seconds": round(time.monotonic() - started, 3),
                "error": f"{type(error).__name__}: {error}"[:200]}


def main() -> None:
    parser = argparse.ArgumentParser(description="Taskbook 20-student concurrent submission benchmark")
    parser.add_argument("--base-url", required=True, help="Backend API base, e.g. http://127.0.0.1:8888/api")
    parser.add_argument("--tokens", required=True, type=Path, help="File of 20 distinct test-account bearer tokens")
    parser.add_argument("--question-id", required=True)
    parser.add_argument("--code-file", required=True, type=Path, help="Correct Python solution for the chosen test question")
    parser.add_argument("--timeout", type=float, default=60.0)
    parser.add_argument("--out", type=Path, default=Path(__file__).resolve().parent / "evidence" / "submission_benchmark.json")
    parser.add_argument("--confirm-run", action="store_true", help="Acknowledge that 20 real submissions will be created")
    args = parser.parse_args()
    if not args.confirm_run:
        parser.error("Pass --confirm-run after preparing 20 test accounts and a disposable test question")
    tokens = [line.strip() for line in args.tokens.read_text(encoding="utf-8").splitlines() if line.strip()]
    if len(tokens) != 20 or len(set(tokens)) != 20:
        parser.error("Exactly 20 different bearer tokens are required")
    code = args.code_file.read_text(encoding="utf-8")
    if not code.strip():
        parser.error("Code file is empty")
    base = args.base_url.rstrip("/")
    identities = [str(call(base + "/user/session/status", token).get("userId")) for token in tokens]
    if "None" in identities or len(set(identities)) != 20:
        parser.error("Tokens must belong to 20 distinct logged-in accounts")
    started = time.monotonic()
    with ThreadPoolExecutor(max_workers=20) as pool:
        futures = [pool.submit(submit_one, base, token, args.question_id, code, args.timeout)
                   for token in tokens]
        results = [future.result() for future in futures]
    wall = time.monotonic() - started
    completed = sorted(row["elapsed_seconds"] for row in results if row.get("status") in (2, 3))
    report = {"run_at_utc": datetime.now(timezone.utc).isoformat(), "base_url": base,
              "question_id": args.question_id, "students": 20,
              "completed": len(completed), "failed_or_timed_out": 20 - len(completed),
              "wall_seconds": round(wall, 3),
              "mean_seconds": round(statistics.mean(completed), 3) if completed else None,
              "max_seconds": max(completed) if completed else None,
              "within_5_seconds": sum(value <= 5 for value in completed),
              "taskbook_passed": len(completed) == 20 and all(value <= 5 for value in completed),
              "results": results}
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({key: report[key] for key in ("completed", "max_seconds", "taskbook_passed")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
