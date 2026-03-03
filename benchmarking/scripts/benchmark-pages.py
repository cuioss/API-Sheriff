#!/usr/bin/env python3
"""Benchmark CI pipeline helper for GitHub Pages deployment.

Manages benchmark history and assembles deployment artifacts for cuioss.github.io.

Subcommands:
    prepare-history  Copy fetched history to working directories for Maven trend calculation.
    assemble         Merge history, enforce retention, and combine artifacts for deployment.

Usage in CI:
    # Before Maven benchmark runs:
    python3 benchmarking/scripts/benchmark-pages.py prepare-history \
        --previous-pages-dir previous-pages/api-sheriff/benchmarks \
        --output-dir "$GITHUB_WORKSPACE/benchmark-history"

    # After all Maven benchmark runs:
    python3 benchmarking/scripts/benchmark-pages.py assemble \
        --integration-results benchmarking/benchmark-integration-wrk/target/benchmark-results/gh-pages-ready \
        --previous-pages-dir previous-pages/api-sheriff/benchmarks \
        --output-dir gh-pages \
        --commit-sha "$COMMIT_SHA"
"""

import argparse
import json
import shutil
import sys
from datetime import datetime, timezone
from pathlib import Path

# Badge files produced by each benchmark type and their names in the root badges/ directory.
_BADGE_MAPPING = {
    "integration": {
        "integration-performance-badge.json": "integration-performance-badge.json",
        "integration-trend-badge.json": "integration-trend-badge.json",
        "last-run-badge.json": "integration-last-run-badge.json",
    },
}

_BENCHMARK_TYPES = ("integration",)
_DEFAULT_MAX_HISTORY = 10


def _copy_json_files(src_dir: Path, dst_dir: Path, *, skip_existing: bool = False) -> int:
    """Copy all .json files from src to dst. Returns count of files copied."""
    dst_dir.mkdir(parents=True, exist_ok=True)
    copied = 0
    for f in src_dir.glob("*.json"):
        dst = dst_dir / f.name
        if skip_existing and dst.exists():
            continue
        shutil.copy2(f, dst)
        copied += 1
    return copied


def _enforce_retention(history_dir: Path, max_files: int) -> int:
    """Keep only the newest max_files JSON files (sorted by name descending). Returns count removed."""
    if not history_dir.is_dir():
        return 0
    files = sorted(history_dir.glob("*.json"), reverse=True)
    removed = 0
    for old_file in files[max_files:]:
        old_file.unlink()
        removed += 1
    return removed


def prepare_history(args: argparse.Namespace) -> None:
    """Copy previously deployed history files to working directories for Maven trend calculation."""
    previous_dir = Path(args.previous_pages_dir)
    output_dir = Path(args.output_dir)

    if not previous_dir.is_dir():
        print(f"No previous pages directory found at {previous_dir}, skipping history preparation")
        return

    for benchmark_type in _BENCHMARK_TYPES:
        history_src = previous_dir / benchmark_type / "history"
        if not history_src.is_dir():
            continue
        count = _copy_json_files(history_src, output_dir / benchmark_type)
        print(f"Prepared {count} {benchmark_type} history files")


def assemble(args: argparse.Namespace) -> None:
    """Merge history, enforce retention, and combine artifacts for deployment.

    Note: History archiving (current run -> history/) is handled by Maven's
    HistoricalDataManager.archiveCurrentRun() during the benchmark verify phase.
    This function only merges previously deployed history and enforces retention.
    """
    now = datetime.now(timezone.utc)
    output_dir = Path(args.output_dir)
    previous_dir = Path(args.previous_pages_dir) if args.previous_pages_dir else None
    commit_sha = args.commit_sha
    max_history = args.max_history

    # Collect configured benchmark modules
    modules: list[tuple[str, Path]] = []
    if args.micro_results:
        modules.append(("micro", Path(args.micro_results)))
    if args.integration_results:
        modules.append(("integration", Path(args.integration_results)))

    if not modules:
        print("Error: at least one of --micro-results or --integration-results is required",
              file=sys.stderr)
        sys.exit(1)

    # 1. Merge previous history (skip files that already exist to avoid overwriting current run)
    if previous_dir and previous_dir.is_dir():
        for name, results_dir in modules:
            prev_history = previous_dir / name / "history"
            if not prev_history.is_dir():
                continue
            history_dir = results_dir / "history"
            count = _copy_json_files(prev_history, history_dir, skip_existing=True)
            print(f"Merged {count} previous {name} history files")

    # 2. Enforce retention policy per module
    for name, results_dir in modules:
        removed = _enforce_retention(results_dir / "history", max_history)
        if removed:
            print(f"Removed {removed} old {name} history files (retention: {max_history})")

    # 3. Combine into output directory
    output_dir.mkdir(parents=True, exist_ok=True)
    badges_dir = output_dir / "badges"
    badges_dir.mkdir(exist_ok=True)

    for name, results_dir in modules:
        if not results_dir.is_dir():
            print(f"Warning: {name} results not found at {results_dir}, skipping")
            continue

        # Copy full module output into type subdirectory
        shutil.copytree(results_dir, output_dir / name, dirs_exist_ok=True)
        print(f"Copied {name} benchmark artifacts to {output_dir / name}")

        # Promote badges to root badges/ directory with mapped names
        module_badges = results_dir / "badges"
        if module_badges.is_dir() and name in _BADGE_MAPPING:
            for src_name, dst_name in _BADGE_MAPPING[name].items():
                src = module_badges / src_name
                if src.is_file():
                    shutil.copy2(src, badges_dir / dst_name)

    # 4. Write deployment metadata
    metadata = {
        "timestamp": now.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "commit": commit_sha,
    }
    (output_dir / "metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")

    # Summary
    print(f"\nAssembled deployment artifacts in {output_dir}/")
    for entry in sorted(output_dir.iterdir()):
        kind = "dir" if entry.is_dir() else "file"
        print(f"  {entry.name}/ " if entry.is_dir() else f"  {entry.name}")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Benchmark CI pipeline helper for GitHub Pages deployment.",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    # prepare-history
    prep = subparsers.add_parser(
        "prepare-history",
        help="Copy fetched history to working directories for Maven trend calculation.",
    )
    prep.add_argument(
        "--previous-pages-dir", required=True,
        help="Path to previously deployed pages (e.g. previous-pages/api-sheriff/benchmarks)",
    )
    prep.add_argument(
        "--output-dir", required=True,
        help="Output directory for history files (passed to Maven as benchmark.history.dir parent)",
    )

    # assemble
    asm = subparsers.add_parser(
        "assemble",
        help="Merge history, enforce retention, and combine all benchmark artifacts for deployment.",
    )
    asm.add_argument(
        "--micro-results",
        help="Path to micro benchmark gh-pages-ready directory",
    )
    asm.add_argument(
        "--integration-results",
        help="Path to integration benchmark gh-pages-ready directory",
    )
    asm.add_argument(
        "--previous-pages-dir",
        help="Path to previously deployed pages for history merging",
    )
    asm.add_argument(
        "--output-dir", required=True,
        help="Output directory for combined deployment artifacts",
    )
    asm.add_argument(
        "--commit-sha", required=True,
        help="Git commit SHA for metadata and history archive naming",
    )
    asm.add_argument(
        "--max-history", type=int, default=_DEFAULT_MAX_HISTORY,
        help=f"Maximum number of history entries to retain (default: {_DEFAULT_MAX_HISTORY})",
    )

    args = parser.parse_args()

    command_handlers = {
        "prepare-history": prepare_history,
        "assemble": assemble,
    }
    command_handlers[args.command](args)


if __name__ == "__main__":
    main()
