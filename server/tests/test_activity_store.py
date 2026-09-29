import os
import tempfile
import unittest
from unittest.mock import patch

from server.linjian_server import State


class ActivityStoreTests(unittest.TestCase):
    def test_guidian_receipts_with_same_correlation_are_stored_once(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {
            "LINJIAN_TOKEN": "test-token-long-enough",
            "LINJIAN_DATA_DIR": directory,
        }):
            state = State()
            base = {
                "device_id": "android-phone", "source": "phone", "type": "guidian_return",
                "action": "guidian_returned", "metadata_json": {"correlation_id": "same-guidian-action"},
            }
            first = state.add_activity_event({**base, "id": "receipt-1"})
            second = state.add_activity_event({**base, "id": "receipt-2"})
            self.assertEqual(first["id"], second["id"])
            self.assertEqual(1, len(state.activity_events))


if __name__ == "__main__":
    unittest.main()
