"""Retain three completed APK builds independently for each registered app."""
import argparse
from datetime import datetime
import json
import os
from pathlib import Path
import sys
import urllib.error
import urllib.request

REPOSITORY = "shadow-pheonix/Android_Development"
POLICY = Path(__file__).resolve().parents[1] / "artifact-retention.json"


class ApiError(RuntimeError):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None  # Never forward the runner token to another host.


class GitHub:
    def __init__(self, token):
        self.token = token
        self.opener = urllib.request.build_opener(NoRedirect())

    def request(self, path, method="GET"):
        request = urllib.request.Request(
            f"https://api.github.com/repos/{REPOSITORY}/{path}",
            headers={"Authorization": f"Bearer {self.token}",
                     "Accept": "application/vnd.github+json",
                     "X-GitHub-Api-Version": "2022-11-28"}, method=method)
        try:
            with self.opener.open(request, timeout=30) as response:
                if method == "DELETE":
                    if response.status != 204:
                        raise ApiError("Unexpected artifact deletion status")
                    return None
                return json.load(response)
        except urllib.error.HTTPError as error:
            code = error.code
            error.close()
            if method == "GET" and code == 404:
                return None
            raise ApiError(f"GitHub {method} failed: HTTP {code}") from None
        except (urllib.error.URLError, ValueError, OSError):
            raise ApiError(f"GitHub {method} failed; cleanup stopped") from None

    def artifacts(self):
        result = []
        for page in range(1, 101):
            data = self.request(f"actions/artifacts?per_page=100&page={page}")
            if not isinstance(data, dict) or not isinstance(data.get("artifacts"), list):
                raise ApiError("Incomplete inventory; nothing deleted")
            result.extend(data["artifacts"])
            if len(data["artifacts"]) < 100:
                if len({a["id"] for a in result}) != len(result):
                    raise ApiError("Inventory changed during pagination; retry later")
                return result
        raise ApiError("Inventory page limit reached; nothing deleted")


def validate_policy(policy):
    if policy.get("keep_builds") != 3 or not isinstance(policy.get("apps"), dict):
        raise ApiError("Policy must keep three builds per app")
    seen = set()
    for app in policy["apps"].values():
        if not app.get("apk_names") or not isinstance(app.get("related_names"), list):
            raise ApiError("App needs APK names and a related-artifact list")
        for name in app["apk_names"] + app["related_names"]:
            if not isinstance(name, str) or not name or name in seen:
                raise ApiError("Artifact names must map to exactly one app")
            seen.add(name)
    return policy


def created(artifact):
    value = datetime.fromisoformat(artifact["created_at"].replace("Z", "+00:00"))
    if value.tzinfo is None:
        raise ApiError("Artifact timestamp lacks timezone")
    return value


def plan_cleanup(api, artifacts, policy):
    policy = validate_policy(policy)
    runs = {}
    plan = []
    kept = {}
    for app_name, app in policy["apps"].items():
        names = set(app["apk_names"] + app["related_names"])
        candidates = [a for a in artifacts if a["name"] in names and not a.get("expired")]
        completed = []
        for artifact in candidates:
            run_id = artifact.get("workflow_run", {}).get("id")
            if not isinstance(run_id, int) or run_id <= 0:
                continue
            if run_id not in runs:
                run = api.request(f"actions/runs/{run_id}")
                if not run or run.get("id") != run_id:
                    raise ApiError("Cannot verify build status; nothing deleted")
                runs[run_id] = run
            if runs[run_id].get("status") == "completed":
                completed.append(artifact)
        # All variants and branch builds of one app share its three-build budget.
        # An unfinished run cannot displace a completed, downloadable APK.
        apk_runs = {}
        for artifact in completed:
            if artifact["name"] in app["apk_names"]:
                run_id = artifact["workflow_run"]["id"]
                apk_runs[run_id] = max(apk_runs.get(run_id, created(artifact)), created(artifact))
        newest = sorted(apk_runs, key=lambda run: (apk_runs[run], run), reverse=True)[:3]
        kept[app_name] = newest
        if len(newest) < 3:
            continue
        cutoff = min(apk_runs[run] for run in newest)
        for artifact in completed:
            run_id = artifact["workflow_run"]["id"]
            if run_id in newest:
                continue
            if artifact["name"] in app["apk_names"] or created(artifact) < cutoff:
                plan.append(artifact)
    return plan, kept


def prune(api, policy, apply=False):
    artifacts = api.artifacts()
    # Finish inventory/status reads before any deletion; an API error aborts safely.
    plan, kept = plan_cleanup(api, artifacts, policy)
    total = sum(a["size_in_bytes"] for a in artifacts if not a.get("expired"))
    print(f"Inventory: {len(artifacts)} artifacts; {total} bytes")
    for app, runs in kept.items():
        print(f"Keep {app}: {len(runs)} completed APK builds; runs={runs}")
    freed = 0
    count = 0
    for artifact in plan:
        if apply:
            current = api.request(f"actions/artifacts/{artifact['id']}")
            if not current or any(current.get(key) != artifact.get(key)
                                  for key in ("name", "created_at", "workflow_run")):
                continue
            run = api.request(f"actions/runs/{artifact['workflow_run']['id']}")
            if (not run or run.get("id") != artifact['workflow_run']['id']
                    or run.get("status") != "completed"):
                continue  # A rerun may have started since the inventory was read.
            api.request(f"actions/artifacts/{artifact['id']}", method="DELETE")
            freed += artifact["size_in_bytes"]
            count += 1
        print(f"{'Deleted' if apply else 'Would delete'} {artifact['name']}: {artifact['id']}")
    summary = (f"{'Deleted' if apply else 'Dry run'}: {count if apply else len(plan)} artifacts; "
               f"{freed if apply else sum(a['size_in_bytes'] for a in plan)} bytes. "
               "Release assets, workflow logs and caches untouched.")
    print(summary)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a") as output:
            output.write(summary + "\n")
    return freed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    if (os.environ.get("GITHUB_ACTIONS") != "true"
            or os.environ.get("GITHUB_REPOSITORY") != REPOSITORY
            or os.environ.get("GITHUB_REF") != "refs/heads/main"):
        raise ApiError("Cleanup requires this repository's trusted main Actions runner")
    token = os.environ.get("GH_TOKEN")
    if not token:
        raise ApiError("Missing runner token")
    with POLICY.open() as source:
        policy = json.load(source)
    prune(GitHub(token), policy, apply=args.apply)


if __name__ == "__main__":
    try:
        main()
    except ApiError as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
    except (KeyError, TypeError, ValueError):
        print("Malformed policy or API inventory; cleanup stopped", file=sys.stderr)
        sys.exit(1)
