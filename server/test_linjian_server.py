import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

import linjian_server


class ImmediateThread:
    def __init__(self, target, **_kwargs):
        self.target = target

    def start(self):
        self.target()


class SlackEventForwardingTests(unittest.TestCase):
    def phone_event(self):
        return {
            "id": "05392753-test-event",
            "device_id": "android-phone",
            "created_at": "2026-09-27T08:17:50Z",
            "source": "phone",
            "type": "app_open",
            "app_name": "Chrome",
            "package_name": "com.android.chrome",
            "action": "foreground_changed",
            "metadata_json": {"previous_package": "com.bbk.launcher2"},
        }

    def test_new_phone_event_is_forwarded_once(self):
        response = Mock()
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        response.read.return_value = b"ok"

        with tempfile.TemporaryDirectory() as data_dir, \
                patch.dict(os.environ, {
                    "LINJIAN_DATA_DIR": data_dir,
                    "SLACK_WEBHOOK_URL": "https://hooks.slack.test/services/example",
                }), \
                patch.object(linjian_server, "Thread", ImmediateThread), \
                patch.object(linjian_server, "urlopen", return_value=response) as mocked_urlopen:
            state = linjian_server.State()
            saved = state.add_activity_event(self.phone_event())
            state.add_activity_event(self.phone_event())

        self.assertEqual(saved["id"], "05392753-test-event")
        mocked_urlopen.assert_called_once()
        request = mocked_urlopen.call_args.args[0]
        payload = json.loads(request.data.decode("utf-8"))
        self.assertEqual(
            payload["text"],
            "PEEPER_EVENT\n"
            "type=app_open\n"
            "source=phone\n"
            "action=foreground_changed\n"
            "app=Chrome\n"
            "package=com.android.chrome\n"
            "previous_package=com.bbk.launcher2\n"
            "occurred_at=2026-09-27T08:17:50Z\n"
            "event_id=05392753-test-event",
        )

    def test_missing_webhook_skips_forwarding_and_still_saves(self):
        with tempfile.TemporaryDirectory() as data_dir, \
                patch.dict(os.environ, {"LINJIAN_DATA_DIR": data_dir}, clear=False), \
                patch.object(linjian_server, "urlopen") as mocked_urlopen:
            os.environ.pop("SLACK_WEBHOOK_URL", None)
            state = linjian_server.State()
            saved = state.add_activity_event(self.phone_event())
            persisted = json.loads(Path(data_dir, "activity_events.json").read_text(encoding="utf-8"))

        self.assertEqual(saved["id"], "05392753-test-event")
        self.assertEqual(persisted[0]["id"], "05392753-test-event")
        mocked_urlopen.assert_not_called()

    def test_webhook_failure_does_not_fail_event_write(self):
        with tempfile.TemporaryDirectory() as data_dir, \
                patch.dict(os.environ, {
                    "LINJIAN_DATA_DIR": data_dir,
                    "SLACK_WEBHOOK_URL": "https://hooks.slack.test/services/example",
                }), \
                patch.object(linjian_server, "Thread", ImmediateThread), \
                patch.object(linjian_server, "urlopen", side_effect=OSError("network down")), \
                patch.object(linjian_server.sys, "stderr", Mock()):
            state = linjian_server.State()
            saved = state.add_activity_event(self.phone_event())
            persisted = json.loads(Path(data_dir, "activity_events.json").read_text(encoding="utf-8"))

        self.assertEqual(saved["id"], "05392753-test-event")
        self.assertEqual(persisted[0]["id"], "05392753-test-event")


if __name__ == "__main__":
    unittest.main()
