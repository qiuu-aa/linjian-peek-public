"""Server-side admission guard for old Android clients and upstream Slack bridges."""
from __future__ import annotations

from typing import Any

CHATGPT_PACKAGE = "com.openai.chatgpt"

SYSTEM_OR_HELPER_EXACT = {
    "android", "com.android.systemui", "com.android.launcher", "com.android.launcher2",
    "com.android.launcher3", "com.google.android.apps.nexuslauncher", "com.miui.home",
    "com.huawei.android.launcher", "com.sec.android.app.launcher", "com.android.intentresolver",
    "com.android.resolver", "com.android.documentsui", "com.google.android.documentsui",
    "com.android.permissioncontroller", "com.google.android.permissioncontroller",
    "com.android.packageinstaller", "com.google.android.packageinstaller",
    "com.google.android.photopicker", "com.google.android.apps.photos.picker", "com.android.providers.media",
    "com.coloros.gallery3d", "com.oplus.gallery", "com.miui.gallery",
}

SYSTEM_OR_HELPER_PARTS = (
    "launcher", "systemui", "keyguard", "fingerprint", "inputmethod", "keyboard",
    "intentresolver", "permissioncontroller", "packageinstaller", "documentsui",
    "photopicker", "sharesheet", "recents", ".aod",
)

LOCAL_ONLY_PACKAGES = {
    "com.android.settings", "com.google.android.apps.wellbeing", "com.coloros.digitalwellbeing",
    "com.oplus.digitalwellbeing", "com.miui.securitycenter", "dev.linjian.peek",
}

CANDIDATE_TYPES = {
    "morning_return_candidate", "afternoon_return_candidate", "late_night_active_candidate",
    "long_app_session_candidate", "shopping_or_takeout_candidate", "travel_candidate",
    "meal_window_candidate", "metric_threshold_candidate",
}

IMPORTANT_PHONE_TYPES = {
    "guidian_return", "guidian_reject", "screen_break_trigger", "whisper_update",
    "calendar_edit", "user_request", "user_message", "action_error",
}


def _metadata(data: dict[str, Any]) -> dict[str, Any]:
    value = data.get("metadata_json", data.get("metadata", {}))
    if isinstance(value, str):
        import json
        try:
            value = json.loads(value)
        except Exception:
            return {}
    return value if isinstance(value, dict) else {}


def package_rule(package_name: str) -> str:
    package = str(package_name or "").strip().lower()
    if not package:
        return ""
    if package in SYSTEM_OR_HELPER_EXACT or any(part in package for part in SYSTEM_OR_HELPER_PARTS):
        return "ignored_system_ui" if ("systemui" in package or "launcher" in package or "keyguard" in package) else "ignored_helper_ui"
    if package in LOCAL_ONLY_PACKAGES:
        return "ignored_helper_ui"
    return ""


def admit_event(data: dict[str, Any]) -> tuple[bool, str]:
    """Return whether this event is allowed beyond the server admission boundary."""
    if str(data.get("source") or "") != "phone":
        return True, "accepted_non_phone"
    event_type = str(data.get("type") or "").strip()
    action = str(data.get("action") or "").strip()
    package = str(data.get("package_name") or data.get("package") or "").strip().lower()
    metadata = _metadata(data)
    ignored = package_rule(package)
    if ignored:
        return False, ignored
    if event_type == "phone_activity" and not action and not str(metadata.get("subtype") or "").strip():
        return False, "suppressed_unknown_phone_activity"
    if event_type == "app_open":
        if package == CHATGPT_PACKAGE:
            return False, "suppressed_chatgpt_open"
        if action != "foreground_changed" or metadata.get("aggregated") is not True:
            return False, "merged_repeat_switches"
        return True, "emitted_candidate"
    if event_type in CANDIDATE_TYPES or event_type in IMPORTANT_PHONE_TYPES:
        return True, "emitted_candidate"
    if not action:
        return False, "suppressed_unknown_phone_activity"
    return True, "emitted_candidate"


def correlation_id(data: dict[str, Any]) -> str:
    return str(_metadata(data).get("correlation_id") or "").strip()[:100]
