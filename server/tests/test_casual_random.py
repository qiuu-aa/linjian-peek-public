import json
import os
import tempfile
import time
import unittest
from unittest.mock import patch

from server.event_policy import admit_event
from server import linjian_server


class ImmediateThread:
    def __init__(self, target, **kwargs):
        self.target = target
    def start(self):
        self.target()


class CasualRandomTests(unittest.TestCase):
    def event(self):
        now = int(time.time() * 1000)
        return {
            "id": "random-daily-target-1", "source": "phone", "type": "casual_random_candidate",
            "action": "casual_random_knock", "package_name": "com.android.chrome",
            "metadata_json": {"knock_index": 1, "plan_total": 2, "requested_count": 3,
                              "planned_at": "2026-10-04T15:00:00+08:00", "actual_at": "2026-10-04T15:10:00+08:00",
                              "delay_minutes": 10, "package_category": "meaningful", "expires_at_ms": now + 10000},
        }

    def test_admitted_and_slack_forwarded_once_with_safe_metadata(self):
        event = self.event()
        self.assertEqual(admit_event(event), (True, "emitted_candidate"))
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {
            "LINJIAN_DATA_DIR": directory, "SLACK_USER_TOKEN": "", "SLACK_BOT_TOKEN": "",
            "SLACK_CHANNEL_ID": "", "SLACK_WEBHOOK_URL": "https://hooks.slack.test/services/example",
        }), patch.object(linjian_server, "Thread", ImmediateThread), patch.object(linjian_server, "_read_slack_response", return_value=b"ok") as send:
            state = linjian_server.State()
            state.add_activity_event(event)
            state.add_activity_event(event)
            send.assert_called_once()
            text = json.loads(send.call_args.args[0].data)["text"]
            self.assertTrue(text.startswith("PEEPER_EVENT\n"))
            for field in ("action=casual_random_knock", "knock_index=1", "plan_total=2", "delay_minutes=10",
                          "package_category=meaningful", "event_id=random-daily-target-1", "planned_at=", "actual_at="):
                self.assertIn(field, text)

    def test_expired_event_not_admitted_or_forwarded(self):
        event = self.event()
        event["metadata_json"]["expires_at_ms"] = 1
        self.assertEqual(admit_event(event), (False, "suppressed_expired_casual_random"))
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {
            "LINJIAN_DATA_DIR": directory, "SLACK_WEBHOOK_URL": "https://hooks.slack.test/services/example",
        }), patch.object(linjian_server, "post_slack_event") as send:
            linjian_server.State().add_activity_event(event)
            send.assert_not_called()

    def test_chatgpt_and_system_apps_are_blocked(self):
        for pkg in ("com.openai.chatgpt", "com.android.systemui", "com.android.settings", "dev.linjian.peek"):
            event = self.event()
            event["package_name"] = pkg
            self.assertFalse(admit_event(event)[0])

    def test_expiry_rechecked_at_actual_slack_send(self):
        event = self.event()
        with patch.object(linjian_server, "_read_slack_response") as send, patch("time.time", return_value=time.time() + 120):
            linjian_server.post_slack_event(event, webhook_url="https://hooks.slack.test/services/example")
            send.assert_not_called()


if __name__ == "__main__":
    unittest.main()
