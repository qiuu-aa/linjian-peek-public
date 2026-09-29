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


class ControlledTimer:
    instances = []

    def __init__(self, interval, target, **_kwargs):
        self.interval = interval
        self.target = target
        self.daemon = False
        self.cancelled = False
        self.started = False
        self.__class__.instances.append(self)

    def start(self):
        self.started = True

    def cancel(self):
        self.cancelled = True

    def fire(self):
        if not self.cancelled:
            self.target()


class SlackEventForwardingTests(unittest.TestCase):
    def setUp(self):
        ControlledTimer.instances = []

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
            "metadata_json": {
                "aggregated": True,
                "from_package": "com.tencent.mm",
                "to_package": "com.android.chrome",
                "transition_count": 2,
                "window_seconds": 300,
            },
        }

    @staticmethod
    def slack_response(body=b'{"ok":true,"ts":"123.456"}'):
        response = Mock()
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        response.read.return_value = body
        return response

    def test_user_token_is_preferred_and_event_is_forwarded_once(self):
        response = self.slack_response()

        with tempfile.TemporaryDirectory() as data_dir, \
                patch.dict(os.environ, {
                    "LINJIAN_DATA_DIR": data_dir,
                    "SLACK_USER_TOKEN": "xoxp-test-token",
                    "SLACK_BOT_TOKEN": "xoxb-test-token",
                    "SLACK_CHANNEL_ID": "C0C4U2ZGBDX",
                    "SLACK_WEBHOOK_URL": "https://hooks.slack.test/services/example",
                    "SLACK_EVENT_DEBOUNCE_SECONDS": "0",
                    "SLACK_EVENT_MIN_INTERVAL_SECONDS": "0",
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
        self.assertEqual(request.full_url, "https://slack.com/api/chat.postMessage")
        self.assertEqual(request.get_header("Authorization"), "Bearer xoxp-test-token")
        self.assertEqual(payload["channel"], "C0C4U2ZGBDX")
        self.assertEqual(
            payload["text"],
            "PEEPER_EVENT\n"
            "type=app_open\n"
            "source=phone\n"
            "action=foreground_changed\n"
            "app=Chrome\n"
            "package=com.android.chrome\n"
            "previous_package=\n"
            "from_package=com.tencent.mm\n"
            "to_package=com.android.chrome\n"
            "transition_count=2\n"
            "window_seconds=300\n"
            "occurred_at=2026-09-27T08:17:50Z\n"
            "event_id=05392753-test-event",
        )

    def test_noise_is_not_forwarded_when_state_is_called_directly(self):
        noisy = self.phone_event()
        noisy["metadata_json"] = {"aggregated": False}
        chatgpt = self.phone_event()
        chatgpt.update({"id": "chatgpt-event", "package_name": "com.openai.chatgpt"})

        with tempfile.TemporaryDirectory() as data_dir, \
                patch.dict(os.environ, {
                    "LINJIAN_DATA_DIR": data_dir,
                    "SLACK_USER_TOKEN": "xoxp-test-token",
                    "SLACK_CHANNEL_ID": "C0C4U2ZGBDX",
                    "SLACK_EVENT_DEBOUNCE_SECONDS": "0",
                    "SLACK_EVENT_MIN_INTERVAL_SECONDS": "0",
                }), \
                patch.object(linjian_server, "Thread", ImmediateThread), \
                patch.object(linjian_server, "urlopen") as mocked_urlopen, \
                patch.object(linjian_server.sys, "stderr", Mock()):
            state = linjian_server.State()
            state.add_activity_event(noisy)
            state.add_activity_event(chatgpt)

        mocked_urlopen.assert_not_called()

    def test_bot_token_is_used_when_user_token_is_missing(self):
        response = self.slack_response()
        with tempfile.TemporaryDirectory() as data_dir, \
                patch.dict(os.environ, {
                    "LINJIAN_DATA_DIR": data_dir,
                    "SLACK_USER_TOKEN": "",
                    "SLACK_BOT_TOKEN": "xoxb-test-token",
                    "SLACK_CHANNEL_ID": "C0C4U2ZGBDX",
                    "SLACK_WEBHOOK_URL": "https://hooks.slack.test/services/example",
                    "SLACK_EVENT_DEBOUNCE_SECONDS": "0",
                    "SLACK_EVENT_MIN_INTERVAL_SECONDS": "0",
                }), \
                patch.object(linjian_server, "Thread", ImmediateThread), \
                patch.object(linjian_server, "urlopen", return_value=response) as mocked_urlopen:
            linjian_server.State().add_activity_event(self.phone_event())

        request = mocked_urlopen.call_args.args[0]
        self.assertEqual(request.full_url, "https://slack.com/api/chat.postMessage")
        self.assertEqual(request.get_header("Authorization"), "Bearer xoxb-test-token")

    def test_webhook_is_used_when_bot_credentials_are_missing(self):
        response = self.slack_response(b"ok")
        with tempfile.TemporaryDirectory() as data_dir, \
                patch.dict(os.environ, {
                    "LINJIAN_DATA_DIR": data_dir,
                    "SLACK_USER_TOKEN": "",
                    "SLACK_BOT_TOKEN": "",
                    "SLACK_CHANNEL_ID": "",
                    "SLACK_WEBHOOK_URL": "https://hooks.slack.test/services/example",
                    "SLACK_EVENT_DEBOUNCE_SECONDS": "0",
                    "SLACK_EVENT_MIN_INTERVAL_SECONDS": "0",
                }), \
                patch.object(linjian_server, "Thread", ImmediateThread), \
                patch.object(linjian_server, "urlopen", return_value=response) as mocked_urlopen:
            linjian_server.State().add_activity_event(self.phone_event())

        request = mocked_urlopen.call_args.args[0]
        self.assertEqual(request.full_url, "https://hooks.slack.test/services/example")
        self.assertIsNone(request.get_header("Authorization"))

    def test_app_switches_are_coalesced_to_the_latest_event(self):
        response = self.slack_response()
        second = self.phone_event()
        second.update({"id": "second-event", "app_name": "Slack", "package_name": "com.Slack"})

        with tempfile.TemporaryDirectory() as data_dir, \
                patch.dict(os.environ, {
                    "LINJIAN_DATA_DIR": data_dir,
                    "SLACK_USER_TOKEN": "",
                    "SLACK_BOT_TOKEN": "xoxb-test-token",
                    "SLACK_CHANNEL_ID": "C0C4U2ZGBDX",
                    "SLACK_WEBHOOK_URL": "",
                    "SLACK_EVENT_DEBOUNCE_SECONDS": "45",
                    "SLACK_EVENT_MIN_INTERVAL_SECONDS": "120",
                }), \
                patch.object(linjian_server, "Timer", ControlledTimer), \
                patch.object(linjian_server, "Thread", ImmediateThread), \
                patch.object(linjian_server, "urlopen", return_value=response) as mocked_urlopen:
            state = linjian_server.State()
            state.add_activity_event(self.phone_event())
            state.add_activity_event(second)
            ControlledTimer.instances[-1].fire()

        self.assertEqual(len(ControlledTimer.instances), 2)
        self.assertTrue(ControlledTimer.instances[0].cancelled)
        mocked_urlopen.assert_called_once()
        payload = json.loads(mocked_urlopen.call_args.args[0].data.decode("utf-8"))
        self.assertIn("event_id=second-event", payload["text"])
        self.assertIn("app=Slack", payload["text"])

    def test_missing_slack_config_skips_forwarding_and_still_saves(self):
        with tempfile.TemporaryDirectory() as data_dir, \
                patch.dict(os.environ, {
                    "LINJIAN_DATA_DIR": data_dir,
                    "SLACK_USER_TOKEN": "",
                    "SLACK_BOT_TOKEN": "",
                    "SLACK_CHANNEL_ID": "",
                    "SLACK_WEBHOOK_URL": "",
                }, clear=False), \
                patch.object(linjian_server, "urlopen") as mocked_urlopen:
            state = linjian_server.State()
            saved = state.add_activity_event(self.phone_event())
            persisted = json.loads(Path(data_dir, "activity_events.json").read_text(encoding="utf-8"))

        self.assertEqual(saved["id"], "05392753-test-event")
        self.assertEqual(persisted[0]["id"], "05392753-test-event")
        mocked_urlopen.assert_not_called()

    def test_slack_api_failure_does_not_fail_event_write(self):
        with tempfile.TemporaryDirectory() as data_dir, \
                patch.dict(os.environ, {
                    "LINJIAN_DATA_DIR": data_dir,
                    "SLACK_USER_TOKEN": "",
                    "SLACK_BOT_TOKEN": "xoxb-test-token",
                    "SLACK_CHANNEL_ID": "C0C4U2ZGBDX",
                    "SLACK_WEBHOOK_URL": "",
                    "SLACK_EVENT_DEBOUNCE_SECONDS": "0",
                    "SLACK_EVENT_MIN_INTERVAL_SECONDS": "0",
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
