#!/usr/bin/env python3
"""Build the packaged CleanStream catalog from generated filter files.

Only filters that have actually been generated are catalogued. This keeps
every visible tile launchable; when a new filter is produced, rerun this
script and rebuild the APK to include it.
"""

import argparse
import json
from collections import OrderedDict
from pathlib import Path


def clean(value):
    return value.strip() if isinstance(value, str) else ""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("filters", type=Path, help="Directory containing filter_<netflix id>.json")
    parser.add_argument("output", type=Path, help="Catalog JSON file to write")
    parser.add_argument("--status", type=Path,
                        help="Optional _status.json; only IDs marked done are catalogued")
    parser.add_argument("--queue", type=Path,
                        help="Optional titles_queue.json used to fill missing catalog metadata by TMDB ID")
    args = parser.parse_args()

    groups = OrderedDict()
    seen = set()
    skipped = []
    completed_ids = None
    status_by_id = {}
    queue_by_tmdb_id = {}
    if args.status:
        status = json.loads(args.status.read_text(encoding="utf-8"))
        completed_ids = {
            str(netflix_id) for netflix_id, details in status.items()
            if isinstance(details, dict) and details.get("status") == "done"
        }
        status_by_id = {
            str(netflix_id): details for netflix_id, details in status.items()
            if isinstance(details, dict) and details.get("status") == "done"
        }
    if args.queue:
        queue = json.loads(args.queue.read_text(encoding="utf-8"))
        queue_titles = queue.get("titles", []) if isinstance(queue, dict) else queue
        if not isinstance(queue_titles, list):
            raise ValueError("Queue file must contain a titles array")
        for entry in queue_titles:
            if not isinstance(entry, dict):
                continue
            tmdb_id = str(entry.get("tmdb_id") or "").strip()
            if tmdb_id and tmdb_id not in queue_by_tmdb_id:
                queue_by_tmdb_id[tmdb_id] = entry

    for path in sorted(args.filters.glob("filter_*.json")):
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            skipped.append(f"{path.name}: {exc}")
            continue

        # The filename is the deployment contract: it is the Netflix ID used
        # by both the deep-link and the on-device filter path. A few older
        # payloads contain a duplicated internal movieID, so do not use it to
        # decide which title this file represents.
        netflix_id = path.stem.removeprefix("filter_")
        if not netflix_id or netflix_id in seen:
            skipped.append(f"{path.name}: missing filename ID or duplicate file")
            continue
        if completed_ids is not None and netflix_id not in completed_ids:
            skipped.append(f"{path.name}: not marked done in {args.status.name}")
            continue
        status_details = status_by_id.get(netflix_id, {})
        queue_details = queue_by_tmdb_id.get(str(status_details.get("tmdb_id") or ""), {})
        title = (clean(data.get("title")) or clean(status_details.get("title"))
                 or clean(queue_details.get("title")))
        if not title:
            skipped.append(f"{path.name}: no title in filter, status, or queue file")
            continue
        seen.add(netflix_id)

        genre = clean(data.get("genre")) or clean(queue_details.get("genre")) or "Other"
        groups.setdefault(genre, []).append({
            "netflix_id": netflix_id,
            "title": title,
            "episode": clean(data.get("episode")),
            "genre": genre,
            "certification": clean(data.get("certification")),
            "poster_url": clean(data.get("poster_url")) or clean(queue_details.get("poster_url")),
            "overview": clean(data.get("overview")) or clean(queue_details.get("overview")),
            "release_date": clean(data.get("release_date")) or clean(queue_details.get("release_date")),
        })

    catalog_groups = []
    for genre in sorted(groups, key=str.casefold):
        titles = sorted(groups[genre], key=lambda item: (item["title"].casefold(), item["episode"].casefold()))
        catalog_groups.append({"name": genre, "titles": titles})

    payload = {"schema_version": 1, "title_count": len(seen), "genres": catalog_groups}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {len(seen)} titles in {len(catalog_groups)} genres to {args.output}")
    if completed_ids is not None:
        print(f"Status marked {len(completed_ids)} IDs done")
    if skipped:
        print(f"Skipped {len(skipped)} files")
        for message in skipped[:10]: print("  " + message)


if __name__ == "__main__":
    main()
