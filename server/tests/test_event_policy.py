import unittest

from server.event_policy import admit_event


def app_event(package: str, *, aggregated: bool = True):
    return {
        "source": "phone", "type": "app_open", "action": "foreground_changed",
        "package_name": package, "metadata_json": {"aggregated": aggregated},
    }


class EventAdmissionTests(unittest.TestCase):
    def test_launcher_and_system_navigation_are_ignored(self):
        self.assertEqual(admit_event(app_event("com.android.launcher3"))[1], "ignored_system_ui")
        self.assertEqual(admit_event(app_event("com.android.systemui"))[1], "ignored_system_ui")

    def test_photo_picker_is_ignored_even_after_stability_window(self):
        accepted, rule = admit_event(app_event("com.google.android.photopicker"))
        self.assertFalse(accepted)
        self.assertEqual(rule, "ignored_helper_ui")

    def test_chatgpt_open_is_not_a_contact_reason(self):
        self.assertEqual(admit_event(app_event("com.openai.chatgpt")), (False, "suppressed_chatgpt_open"))

    def test_chatgpt_late_night_stages_are_admitted(self):
        stages = ((1, 10, "soft", "late_night_soft_checkin"),
                  (2, 30, "firm", "late_night_followup"),
                  (3, 60, "strict", "late_night_persistent_followup"),
                  (4, 90, "strict", "late_night_persistent_followup"),
                  (5, 120, "strict", "late_night_persistent_followup"))
        for stage, minutes, tone, action in stages:
            with self.subTest(stage=stage):
                self.assertEqual(admit_event({
                    "source": "phone", "type": "late_night_active_candidate", "action": action,
                    "package_name": "com.openai.chatgpt", "metadata_json": {
                        "late_night_stage": stage, "reminder_tone": tone, "session_minutes": minutes,
                    },
                }), (True, "emitted_candidate"))

    def test_old_unaggregated_foreground_events_are_blocked(self):
        self.assertEqual(admit_event(app_event("com.tencent.mm", aggregated=False))[0], False)

    def test_aggregated_business_app_transition_is_accepted(self):
        self.assertEqual(admit_event(app_event("com.android.chrome")), (True, "emitted_candidate"))

    def test_empty_phone_activity_is_blocked(self):
        self.assertEqual(admit_event({"source": "phone", "type": "phone_activity"}),
                         (False, "suppressed_unknown_phone_activity"))

    def test_semantic_candidates_are_accepted(self):
        for event_type in (
            "morning_return_candidate", "afternoon_return_candidate", "late_night_active_candidate",
            "long_app_session_candidate", "shopping_or_takeout_candidate", "travel_candidate",
            "meal_window_candidate",
        ):
            with self.subTest(event_type=event_type):
                self.assertTrue(admit_event({"source": "phone", "type": event_type, "action": "threshold"})[0])

    def test_existing_non_phone_important_event_is_preserved(self):
        self.assertTrue(admit_event({"source": "companion", "type": "command", "action": "send_notification"})[0])


if __name__ == "__main__":
    unittest.main()
