"""Check the independently identified RuleUp omissions, without publishing consumer source.

Usage: python scripts/verify-ruleup-catalog.py <project-catalog.json> [old-project-catalog.json]
This is a regression list, not a screen recall denominator.
"""
import json
from pathlib import Path
import sys

report = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
definitions = {item["id"]: item for item in report["composables"]}
referenced = set().union(*(set(item["composableIds"])
    for kind in ("screenCandidates", "entryBindings", "uiRoots") for item in report[kind]))
assert referenced <= definitions.keys(), "Dangling composable ID"
local = {key for key, item in definitions.items() if not item.get("external")}
assert len(local) == report["summary"]["collectedComposables"]
assert local - referenced == set(report["unlinkedComposableIds"])
assert report["analysis"]["engine"] == "SOOTUP_HEROS"
assert report["analysis"]["linkedCallSites"] > 0

expected = {
    "HomeScreen.kt": ["WeekStamps", "RoutineList", "DayResultSection", "DayGroup"],
    "RoomFeedTab.kt": ["ThreadItemCard", "FeedEmptyState"],
    "RoomRankingTab.kt": ["MyRankSummary", "MemberRankRow", "MyChallengeRankSummary", "ChallengeRankRow"],
    "ChallengeConfirmScreen.kt": ["HeaderCard", "SummaryCard"],
    "MyChallengesScreen.kt": ["InProgressCard", "FinishedCard"],
    "ChallengeSettingsScreen.kt": ["LockedRow", "RankingVisibleSection"],
    "ChallengeTargetsScreen.kt": ["CategoryChip"],
    "ChallengeInputScreen.kt": ["TemplateSkeleton"],
    "ChallengeWatchersScreen.kt": ["WatcherRow"],
}
for file, names in expected.items():
    for name in names:
        matches = [item for item in definitions.values() if not item.get("external")
            and item["name"].split(".")[-1] == name and item["source"]["path"].endswith("/" + file)]
        assert len(matches) == 1, (file, name, "missing or ambiguous declaration")
        assert matches[0]["id"] in referenced, (file, name, "unlinked regression")
print("19 independently identified omissions are now linked; all local declarations are preserved.")
if len(sys.argv) > 2:
    old = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
    old_references = set().union(*(set(item["composableIds"])
        for kind in ("screenCandidates", "entryBindings", "uiRoots") for item in old[kind]))
    assert old_references <= referenced, "A previously linked function disappeared"
    print("Additional local references:", len((referenced - old_references) & local))
print(json.dumps(report["summary"], indent=2))
