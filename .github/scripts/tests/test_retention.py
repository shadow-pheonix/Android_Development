"""Per-app build retention uses synthetic artifacts; no network or credentials."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import unittest
from unittest.mock import patch
import urllib.error

SCRIPT = Path(__file__).resolve().parents[1] / "prune-artifacts.py"
spec = importlib.util.spec_from_file_location("retention", SCRIPT)
storage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(storage)
POLICY = {"keep_builds": 3, "apps": {
    "First": {"apk_names": ["first-debug", "first-release"], "related_names": ["first-source"]},
    "Second": {"apk_names": ["second-debug"], "related_names": []}}}


def artifact(number, name="first-debug", run=None, **changes):
    result = {"id": number, "name": name, "expired": False, "size_in_bytes": 100,
              "created_at": f"2026-10-{number:02d}T00:00:00Z",
              "workflow_run": {"id": run if run is not None else number,
                               "head_branch": "main", "head_sha": "a" * 40}}
    result.update(changes)
    return result


class FakeGitHub:
    def __init__(self, items=None):
        self.items = items if items is not None else [artifact(n) for n in [1, 5, 3, 2, 4]]
        self.runs = {a["workflow_run"]["id"]: {"id": a["workflow_run"]["id"],
                                               "status": "completed"} for a in self.items}
        self.deleted = []
        self.error_path = None
        self.revalidation = {}

    def artifacts(self):
        return copy.deepcopy(self.items)

    def request(self, path, method="GET"):
        if path == self.error_path:
            raise storage.ApiError("synthetic failure")
        if path.startswith("actions/runs/"):
            return self.runs.get(int(path.rsplit("/", 1)[1]))
        number = int(path.rsplit("/", 1)[1])
        if method == "DELETE":
            self.deleted.append(number)
            return None
        return self.revalidation.get(number, next(a for a in self.items if a["id"] == number))


class RetentionCase(unittest.TestCase):
    def setUp(self):
        self.api = FakeGitHub()
        quiet = patch("builtins.print")
        quiet.start()
        self.addCleanup(quiet.stop)
        environment = patch.dict(os.environ, {}, clear=True)
        environment.start()
        self.addCleanup(environment.stop)

    def test_keep_three_newest_builds_per_app_independently(self):
        self.api.items.extend([artifact(10, "second-debug"), artifact(11, "second-debug")])
        self.api.runs.update({n: {"id": n, "status": "completed"} for n in [10, 11]})
        self.assertEqual(200, storage.prune(self.api, POLICY, apply=True))
        self.assertEqual([1, 2], self.api.deleted)

    def test_apk_variants_share_one_app_budget(self):
        self.api.items[1]["name"] = "first-release"
        self.api.items[4]["name"] = "first-release"
        storage.prune(self.api, POLICY, apply=True)
        self.assertEqual([1, 2], self.api.deleted)

    def test_same_build_with_multiple_artifacts_counts_once(self):
        self.api.items.append(artifact(6, "first-release", run=5))
        storage.prune(self.api, POLICY, apply=True)
        self.assertEqual([1, 2], self.api.deleted)

    def test_in_progress_run_never_displaces_completed_build(self):
        self.api.runs[5]["status"] = "in_progress"
        storage.prune(self.api, POLICY, apply=True)
        self.assertEqual([1], self.api.deleted)

    def test_source_bundles_of_kept_builds_preserved_older_bundles_removed(self):
        self.api.items.extend([artifact(12, "first-source", run=5),
                               artifact(13, "first-source", run=1,
                                        created_at="2026-10-01T00:00:00Z")])
        storage.prune(self.api, POLICY, apply=True)
        self.assertEqual([1, 2, 13], self.api.deleted)

    def test_recent_source_only_bundle_preserved(self):
        self.api.items.append(artifact(6, "first-source"))
        self.api.runs[6] = {"id": 6, "status": "completed"}
        storage.prune(self.api, POLICY, apply=True)
        self.assertEqual([1, 2], self.api.deleted)

    def test_source_only_old_build_pruned_after_three_newer_apk_builds(self):
        self.api.items[0]["name"] = "first-source"
        storage.prune(self.api, POLICY, apply=True)
        self.assertEqual([1, 2], self.api.deleted)

    def test_fewer_than_three_apk_builds_preserve_source_bundles(self):
        self.api.items = [artifact(1, "first-source"), artifact(2), artifact(3)]
        self.assertEqual(0, storage.prune(self.api, POLICY, apply=True))
        self.assertEqual([], self.api.deleted)

    def test_unknown_artifacts_and_expired_artifacts_are_not_deleted(self):
        self.api.items[0]["expired"] = True
        self.api.items.append(artifact(6, "unrelated-evidence"))
        storage.prune(self.api, POLICY, apply=True)
        self.assertEqual([2], self.api.deleted)

    def test_dry_run_deletes_nothing(self):
        self.assertEqual(0, storage.prune(self.api, POLICY))
        self.assertEqual([], self.api.deleted)

    def test_build_status_failure_prevents_every_deletion(self):
        self.api.error_path = "actions/runs/4"
        with self.assertRaises(storage.ApiError):
            storage.prune(self.api, POLICY, apply=True)
        self.assertEqual([], self.api.deleted)

    def test_rerun_and_changed_identity_preserve_old_artifact(self):
        self.api.revalidation[1] = artifact(1, name="unrelated-evidence")
        original = self.api.request
        count = 0
        def request(path, method="GET"):
            nonlocal count
            if path == "actions/runs/2":
                count += 1
                if count == 2:
                    return {"id": 2, "status": "in_progress"}
            return original(path, method)
        with patch.object(self.api, "request", side_effect=request):
            self.assertEqual(0, storage.prune(self.api, POLICY, apply=True))
        self.assertEqual([], self.api.deleted)

    def test_delete_failure_stops_remaining_deletions(self):
        original = self.api.request
        def request(path, method="GET"):
            if method == "DELETE":
                raise storage.ApiError("synthetic deletion failure")
            return original(path, method)
        with patch.object(self.api, "request", side_effect=request):
            with self.assertRaises(storage.ApiError):
                storage.prune(self.api, POLICY, apply=True)
        self.assertEqual([], self.api.deleted)

    def test_policy_cannot_assign_names_to_multiple_apps_or_change_keep_count(self):
        policy = copy.deepcopy(POLICY)
        policy["apps"]["Second"]["apk_names"].append("first-debug")
        with self.assertRaises(storage.ApiError):
            storage.validate_policy(policy)
        with self.assertRaises(storage.ApiError):
            storage.validate_policy({**POLICY, "keep_builds": 0})

    def test_repository_policy_covers_legacy_names_and_separates_apps(self):
        with storage.POLICY.open() as source:
            policy = storage.validate_policy(json.load(source))
        items = [artifact(n, name) for n, name in enumerate(
            ["dietlog-debug"] * 4 + ["nova-pip-latest-upstream"] * 4 + ["github-test-app-debug"], 1)]
        api = FakeGitHub(items)
        plan, kept = storage.plan_cleanup(api, items, policy)
        self.assertEqual([1, 5], [a["id"] for a in plan])
        self.assertEqual({"DietLog": 3, "Nova": 3, "LegacyTest": 1},
                         {app: len(runs) for app, runs in kept.items()})

    def test_cli_refuses_local_runner_other_repo_or_feature_branch(self):
        valid = {"GITHUB_ACTIONS": "true", "GITHUB_REPOSITORY": storage.REPOSITORY,
                 "GITHUB_REF": "refs/heads/main", "GH_TOKEN": "synthetic-token"}
        for key, value in [("GITHUB_ACTIONS", "false"),
                           ("GITHUB_REPOSITORY", "other/repo"), ("GITHUB_REF", "refs/heads/feature")]:
            with patch.dict(os.environ, {**valid, key: value}, clear=True), patch.object(storage.sys, "argv", [str(SCRIPT), "--apply"]):
                with self.assertRaises(storage.ApiError):
                    storage.main()


class ApiCase(unittest.TestCase):
    def test_all_pages_required_and_duplicate_inventory_aborts(self):
        api = storage.GitHub("synthetic-token")
        page = [{"id": n} for n in range(100)]
        with patch.object(api, "request", side_effect=[{"artifacts": page}, {"artifacts": [{"id": 100}]}]):
            self.assertEqual(101, len(api.artifacts()))
        for last in [None, {"artifacts": [{"id": 1}]}]:
            with patch.object(api, "request", side_effect=[{"artifacts": page}, last]):
                with self.assertRaises(storage.ApiError):
                    api.artifacts()

    def test_api_failure_does_not_print_token_response_or_private_error(self):
        api = storage.GitHub("synthetic-token")
        with patch.object(api.opener, "open", side_effect=urllib.error.HTTPError(
                "https://api.github.com", 403, "synthetic-private-response", {}, None)):
            with self.assertRaises(storage.ApiError) as result:
                api.request("actions/artifacts/1", "DELETE")
            self.assertEqual("GitHub DELETE failed: HTTP 403", str(result.exception))
        self.assertIsNone(storage.NoRedirect().redirect_request(None, None, 302, "", {}, "https://other.example"))


if __name__ == "__main__":
    unittest.main()
