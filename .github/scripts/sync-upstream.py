import argparse
import subprocess
import sys

UPSTREAM_REMOTE = "upstream"
UPSTREAM_URL = "https://github.com/yuzono/anime-extensions.git"
UPSTREAM_BRANCH = "master"
SYNC_BRANCH = "sync"


def git(*args: str, check: bool = True) -> str:
    result = subprocess.run(
        ["git", *args],
        capture_output=True,
        text=True,
    )

    if check and result.returncode != 0:
        print(result.stderr.strip())
        sys.exit(result.returncode)

    return result.stdout


def ensure_upstream_remote() -> None:
    if subprocess.run(
        ["git", "remote", "get-url", UPSTREAM_REMOTE],
        capture_output=True,
    ).returncode != 0:
        git("remote", "add", UPSTREAM_REMOTE, UPSTREAM_URL)


def ensure_clean_tree() -> None:
    if git("status", "--porcelain").strip():
        print("Working tree is not clean")
        sys.exit(1)


def parse_name_status(output: str) -> list[tuple[str, list[str]]]:
    tokens = output.rstrip("\0").split("\0")
    entries = []
    i = 0

    while i < len(tokens) and tokens[i]:
        status = tokens[i]
        i += 1
        path_count = 2 if status[0] in {"R", "C"} else 1
        entries.append((status, tokens[i:i + path_count]))
        i += path_count

    return entries


def is_preserved(path: str) -> bool:
    return path == ".github" or path.startswith(".github/")


def sync_unit(path: str) -> str | None:
    if is_preserved(path):
        return None

    parts = path.split("/")

    if len(parts) >= 3 and parts[0] == "src":
        return "/".join(parts[:3])

    if len(parts) >= 2 and parts[0] in {"lib", "lib-multisrc"}:
        return "/".join(parts[:2])

    return path


def changed_entries(base: str, ref: str) -> list[tuple[str, list[str]]]:
    output = git("diff", "--name-status", "--find-renames", "-z", base, ref)
    return parse_name_status(output)


def collect_units(entries: list[tuple[str, list[str]]]) -> tuple[list[str], list[str]]:
    units = set()
    preserved = set()

    for _, paths in entries:
        for path in paths:
            unit = sync_unit(path)

            if unit is None:
                preserved.add(path)
            else:
                units.add(unit)

    return sorted(units), sorted(preserved)


def path_exists(ref: str, path: str) -> bool:
    return subprocess.run(
        ["git", "cat-file", "-e", f"{ref}:{path}"],
        capture_output=True,
    ).returncode == 0


def update_sync_branch(upstream_ref: str, push: bool) -> None:
    if push:
        git("push", "origin", f"{upstream_ref}:refs/heads/{SYNC_BRANCH}", "--force")
    else:
        print(f"Would update origin/{SYNC_BRANCH} from {upstream_ref}")


def print_plan(
    base: str,
    upstream_ref: str,
    upstream_units: list[str],
    preserved_paths: list[str],
    main_only_units: list[str],
) -> None:
    commits = git("rev-list", "--count", f"{base}..{upstream_ref}").strip()

    print(f"Base: {base}")
    print(f"Upstream commits: {commits}")
    print(f"Upstream units to apply: {len(upstream_units)}")

    for unit in upstream_units:
        print(f"  upstream: {unit}")

    if preserved_paths:
        print(f"Preserved .github paths: {len(preserved_paths)}")

    if main_only_units:
        print(f"Main-only units preserved: {len(main_only_units)}")

        for unit in main_only_units:
            print(f"  main: {unit}")


def apply_units(upstream_ref: str, units: list[str]) -> None:
    git("merge", "--no-ff", "--no-commit", "-s", "ours", upstream_ref)

    for unit in units:
        print(f"Applying {unit}")
        git("rm", "-r", "--ignore-unmatch", "--quiet", "--", unit)

        if path_exists(upstream_ref, unit):
            git("restore", f"--source={upstream_ref}", "--staged", "--worktree", "--", unit)

    git("diff", "--check")
    git("commit", "-m", "Sync upstream")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--push", action="store_true")
    args = parser.parse_args()

    if args.dry_run and args.push:
        print("Use either --dry-run or --push")
        sys.exit(1)

    ensure_clean_tree()
    ensure_upstream_remote()

    git("fetch", "origin")
    git("fetch", UPSTREAM_REMOTE, UPSTREAM_BRANCH)

    upstream_ref = f"{UPSTREAM_REMOTE}/{UPSTREAM_BRANCH}"
    base = git("merge-base", "HEAD", upstream_ref).strip()

    upstream_entries = changed_entries(base, upstream_ref)
    upstream_units, preserved_paths = collect_units(upstream_entries)

    main_entries = changed_entries(base, "HEAD")
    main_units, _ = collect_units(main_entries)
    main_only_units = sorted(set(main_units) - set(upstream_units))

    print_plan(base, upstream_ref, upstream_units, preserved_paths, main_only_units)

    update_sync_branch(upstream_ref, push=args.push)

    if not upstream_units:
        print("No upstream changes to apply")
        return

    if args.dry_run or not args.push:
        print("Dry run only; no changes were applied")
        return

    apply_units(upstream_ref, upstream_units)
    git("push", "origin", "HEAD:main")


if __name__ == "__main__":
    main()
