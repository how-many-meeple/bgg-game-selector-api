"""
Tests for prefetch API endpoints and prefetch_worker handler following RightBICEP principles.
"""

import json
import os
import unittest
from unittest.mock import MagicMock, patch

from boardgame.prefetch_status import (
    COMPLETED,
    FAILED,
    NOT_FOUND,
    PENDING,
    PROCESSING,
    SQLitePrefetchStatusStore,
    SourceType,
)


def make_sqlite_store(db_path):
    return SQLitePrefetchStatusStore(db_path=db_path)


class TestPrefetchEndpoint(unittest.TestCase):

    def setUp(self):
        self._db_path = "test_prefetch_endpoints.sqlite"
        os.environ["CACHE_BACKEND"] = "sqlite"
        os.environ["PREFETCH_SQS_URL"] = (
            "https://sqs.us-east-1.amazonaws.com/123/test-queue"
        )

        self._status_store = make_sqlite_store(self._db_path)

        with (
            patch("app.BoardGameFactory.create_game_cache", return_value=MagicMock()),
            patch("app.BoardGameFactory.create_vector_store", return_value=MagicMock()),
            patch(
                "app.BoardGameFactory.create_prefetch_status_store",
                return_value=self._status_store,
            ),
        ):
            import app as app_module

            self._app_module = app_module
            app_module.prefetch_status_store = self._status_store
            self._client = app_module.app.test_client()

    def tearDown(self):
        if hasattr(self._status_store, "_conn"):
            self._status_store._conn.close()
        if os.path.exists(self._db_path):
            os.remove(self._db_path)

    # RIGHT: Are the results right?

    def test_prefetch_returns_202_and_pending_status(self):
        with patch("app.boto3") as mock_boto3:
            mock_boto3.client.return_value.send_message = MagicMock()
            response = self._client.post(
                "/prefetch",
                json={"source_type": "collection", "source_id": "testuser"},
            )
        self.assertEqual(response.status_code, 202)
        data = json.loads(response.data)
        self.assertEqual(data["status"], PENDING)
        self.assertEqual(data["source_type"], "collection")
        self.assertEqual(data["source_id"], "testuser")

    def test_prefetch_enqueues_to_sqs(self):
        with patch("app.boto3") as mock_boto3:
            mock_sqs = MagicMock()
            mock_boto3.client.return_value = mock_sqs
            self._client.post(
                "/prefetch",
                json={"source_type": "collection", "source_id": "testuser"},
            )
        mock_sqs.send_message.assert_called_once()
        call_kwargs = mock_sqs.send_message.call_args[1]
        body = json.loads(call_kwargs["MessageBody"])
        self.assertEqual(body["source_type"], "collection")
        self.assertEqual(body["source_id"], "testuser")

    def test_prefetch_writes_pending_status_to_store(self):
        with patch("app.boto3"):
            self._client.post(
                "/prefetch",
                json={"source_type": "collection", "source_id": "testuser"},
            )
        status = self._status_store.get(SourceType.COLLECTION, "testuser")
        self.assertIsNotNone(status)
        self.assertEqual(status["status"], PENDING)

    def test_prefetch_returns_200_when_already_pending(self):
        self._status_store.set(SourceType.COLLECTION, "testuser", PENDING)
        with patch("app.boto3") as mock_boto3:
            mock_boto3.client.return_value.send_message = MagicMock()
            response = self._client.post(
                "/prefetch",
                json={"source_type": "collection", "source_id": "testuser"},
            )
        self.assertEqual(response.status_code, 200)
        mock_boto3.client.return_value.send_message.assert_not_called()

    def test_prefetch_returns_200_when_already_completed(self):
        self._status_store.set(SourceType.COLLECTION, "testuser", COMPLETED)
        with patch("app.boto3") as mock_boto3:
            mock_boto3.client.return_value.send_message = MagicMock()
            response = self._client.post(
                "/prefetch",
                json={"source_type": "collection", "source_id": "testuser"},
            )
        self.assertEqual(response.status_code, 200)
        mock_boto3.client.return_value.send_message.assert_not_called()

    def test_prefetch_requeues_when_failed(self):
        self._status_store.set(SourceType.COLLECTION, "testuser", FAILED)
        with patch("app.boto3") as mock_boto3:
            mock_boto3.client.return_value.send_message = MagicMock()
            response = self._client.post(
                "/prefetch",
                json={"source_type": "collection", "source_id": "testuser"},
            )
        self.assertEqual(response.status_code, 202)
        mock_boto3.client.return_value.send_message.assert_called_once()

    def test_prefetch_status_endpoint_returns_current_status(self):
        self._status_store.set(SourceType.COLLECTION, "testuser", PROCESSING)
        response = self._client.get("/prefetch/status/collection/testuser")
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.data)
        self.assertEqual(data["status"], PROCESSING)

    def test_prefetch_status_returns_404_when_unknown(self):
        response = self._client.get("/prefetch/status/collection/unknownuser")
        self.assertEqual(response.status_code, 404)
        data = json.loads(response.data)
        self.assertEqual(data["status"], "unknown")

    # BOUNDARY: Are all the boundary conditions correct?

    def test_prefetch_returns_400_for_missing_source_type(self):
        response = self._client.post("/prefetch", json={"source_id": "testuser"})
        self.assertEqual(response.status_code, 400)

    def test_prefetch_returns_400_for_missing_source_id(self):
        response = self._client.post("/prefetch", json={"source_type": "collection"})
        self.assertEqual(response.status_code, 400)

    def test_prefetch_returns_400_for_invalid_source_type(self):
        response = self._client.post(
            "/prefetch", json={"source_type": "invalid", "source_id": "testuser"}
        )
        self.assertEqual(response.status_code, 400)

    def test_prefetch_returns_400_for_empty_source_id(self):
        response = self._client.post(
            "/prefetch", json={"source_type": "collection", "source_id": "  "}
        )
        self.assertEqual(response.status_code, 400)

    def test_prefetch_returns_400_for_no_body(self):
        response = self._client.post("/prefetch", content_type="application/json")
        self.assertEqual(response.status_code, 400)

    def test_prefetch_status_returns_400_for_invalid_source_type(self):
        response = self._client.get("/prefetch/status/invalid/testuser")
        self.assertEqual(response.status_code, 400)

    def test_geeklist_source_type_is_accepted(self):
        with patch("app.boto3"):
            response = self._client.post(
                "/prefetch",
                json={"source_type": "geeklist", "source_id": "12345"},
            )
        self.assertEqual(response.status_code, 202)


class TestCollectionEndpointPrefetchIntegration(unittest.TestCase):
    """Tests that collection/geeklist routes honour prefetch status."""

    def setUp(self):
        self._db_path = "test_prefetch_collection.sqlite"
        os.environ["CACHE_BACKEND"] = "sqlite"

        self._status_store = make_sqlite_store(self._db_path)

        with (
            patch("app.BoardGameFactory.create_game_cache", return_value=MagicMock()),
            patch("app.BoardGameFactory.create_vector_store", return_value=MagicMock()),
            patch(
                "app.BoardGameFactory.create_prefetch_status_store",
                return_value=self._status_store,
            ),
        ):
            import app as app_module

            app_module.prefetch_status_store = self._status_store
            self._client = app_module.app.test_client()

    def tearDown(self):
        if hasattr(self._status_store, "_conn"):
            self._status_store._conn.close()
        if os.path.exists(self._db_path):
            os.remove(self._db_path)

    def test_collection_returns_404_when_prefetch_is_not_found(self):
        self._status_store.set(
            SourceType.COLLECTION,
            "missinguser",
            NOT_FOUND,
            reason="No user found called 'missinguser'",
        )
        response = self._client.get("/collection/missinguser")
        self.assertEqual(response.status_code, 404)
        data = json.loads(response.data)
        self.assertIn("missinguser", data["error"])

    def test_collection_returns_503_when_prefetch_failed(self):
        self._status_store.set(
            SourceType.COLLECTION, "testuser", FAILED, reason="BGG timed out"
        )
        response = self._client.get("/collection/testuser")
        self.assertEqual(response.status_code, 503)
        data = json.loads(response.data)
        self.assertIn("BGG timed out", data["error"])

    def test_collection_proceeds_to_bgg_when_no_prefetch_status(self):
        mock_selector = MagicMock()
        mock_selector.get_games_matching_filter.return_value = []
        with patch(
            "app.BoardGameFactory.create_player_selector", return_value=mock_selector
        ):
            response = self._client.get("/collection/testuser")
        self.assertEqual(response.status_code, 200)

    def test_collection_proceeds_when_prefetch_is_pending(self):
        # pending = in flight, but collection endpoint should still attempt BGG
        self._status_store.set(SourceType.COLLECTION, "testuser", PENDING)
        mock_selector = MagicMock()
        mock_selector.get_games_matching_filter.return_value = []
        with patch(
            "app.BoardGameFactory.create_player_selector", return_value=mock_selector
        ):
            response = self._client.get("/collection/testuser")
        self.assertEqual(response.status_code, 200)

    def test_geeklist_returns_404_when_prefetch_is_not_found(self):
        self._status_store.set(
            SourceType.GEEKLIST, "99999", NOT_FOUND, reason="List not found"
        )
        response = self._client.get("/geeklist/99999")
        self.assertEqual(response.status_code, 404)

    def test_geeklist_returns_503_when_prefetch_failed(self):
        self._status_store.set(SourceType.GEEKLIST, "99999", FAILED, reason="Timeout")
        response = self._client.get("/geeklist/99999")
        self.assertEqual(response.status_code, 503)


class TestPrefetchWorker(unittest.TestCase):

    def setUp(self):
        self._db_path = "test_prefetch_worker.sqlite"
        os.environ["CACHE_BACKEND"] = "sqlite"
        self._status_store = make_sqlite_store(self._db_path)

    def tearDown(self):
        if hasattr(self._status_store, "_conn"):
            self._status_store._conn.close()
        if os.path.exists(self._db_path):
            os.remove(self._db_path)

    def _make_sqs_event(self, source_type, source_id):
        return {
            "Records": [
                {
                    "body": json.dumps(
                        {"source_type": source_type, "source_id": source_id}
                    )
                }
            ]
        }

    # RIGHT: Are the results right?

    def test_worker_sets_completed_on_success(self):
        mock_selector = MagicMock()
        mock_selector.get_games_matching_filter.return_value = []

        with (
            patch("prefetch_worker._status_store", self._status_store),
            patch(
                "prefetch_worker.BoardGameFactory.create_player_selector",
                return_value=mock_selector,
            ),
        ):
            import prefetch_worker

            prefetch_worker.handler(
                self._make_sqs_event("collection", "testuser"), None
            )

        status = self._status_store.get(SourceType.COLLECTION, "testuser")
        self.assertEqual(status["status"], COMPLETED)

    def test_worker_sets_not_found_on_user_not_found(self):
        from boardgame.board_game import BoardGameUserNotFoundError

        with (
            patch("prefetch_worker._status_store", self._status_store),
            patch(
                "prefetch_worker.BoardGameFactory.create_player_selector",
                side_effect=BoardGameUserNotFoundError(
                    None, "No user found called 'ghost'"
                ),
            ),
        ):
            import prefetch_worker

            prefetch_worker.handler(self._make_sqs_event("collection", "ghost"), None)

        status = self._status_store.get(SourceType.COLLECTION, "ghost")
        self.assertEqual(status["status"], NOT_FOUND)
        self.assertIn("ghost", status["reason"])

    def test_worker_sets_not_found_on_list_not_found(self):
        from boardgame.board_game import BoardGameListNotFoundError

        with (
            patch("prefetch_worker._status_store", self._status_store),
            patch(
                "prefetch_worker.BoardGameFactory.create_list_selector",
                side_effect=BoardGameListNotFoundError("List not found '99999'"),
            ),
        ):
            import prefetch_worker

            prefetch_worker.handler(self._make_sqs_event("geeklist", "99999"), None)

        status = self._status_store.get(SourceType.GEEKLIST, "99999")
        self.assertEqual(status["status"], NOT_FOUND)

    def test_worker_sets_failed_on_unexpected_error(self):
        with (
            patch("prefetch_worker._status_store", self._status_store),
            patch(
                "prefetch_worker.BoardGameFactory.create_player_selector",
                side_effect=RuntimeError("connection reset"),
            ),
        ):
            import prefetch_worker

            prefetch_worker.handler(
                self._make_sqs_event("collection", "testuser"), None
            )

        status = self._status_store.get(SourceType.COLLECTION, "testuser")
        self.assertEqual(status["status"], FAILED)
        self.assertIn("connection reset", status["reason"])

    def test_worker_sets_processing_before_fetching(self):
        call_order = []

        def record_status(*args, **kwargs):
            status = self._status_store.get(SourceType.COLLECTION, "testuser")
            if status:
                call_order.append(status["status"])

        mock_selector = MagicMock()
        mock_selector.get_games_matching_filter.side_effect = record_status

        with (
            patch("prefetch_worker._status_store", self._status_store),
            patch(
                "prefetch_worker.BoardGameFactory.create_player_selector",
                return_value=mock_selector,
            ),
        ):
            import prefetch_worker

            prefetch_worker.handler(
                self._make_sqs_event("collection", "testuser"), None
            )

        self.assertIn(PROCESSING, call_order)

    # BOUNDARY: Are all the boundary conditions correct?

    def test_worker_rejects_invalid_source_type(self):
        with patch("prefetch_worker._status_store", self._status_store):
            import prefetch_worker

            with self.assertRaises(ValueError):
                prefetch_worker.handler(
                    self._make_sqs_event("invalid", "testuser"), None
                )

    def test_worker_handles_multiple_records(self):
        mock_selector = MagicMock()
        mock_selector.get_games_matching_filter.return_value = []
        event = {
            "Records": [
                {
                    "body": json.dumps(
                        {"source_type": "collection", "source_id": "user1"}
                    )
                },
                {
                    "body": json.dumps(
                        {"source_type": "collection", "source_id": "user2"}
                    )
                },
            ]
        }
        with (
            patch("prefetch_worker._status_store", self._status_store),
            patch(
                "prefetch_worker.BoardGameFactory.create_player_selector",
                return_value=mock_selector,
            ),
        ):
            import prefetch_worker

            prefetch_worker.handler(event, None)

        self.assertEqual(
            self._status_store.get(SourceType.COLLECTION, "user1")["status"], COMPLETED
        )
        self.assertEqual(
            self._status_store.get(SourceType.COLLECTION, "user2")["status"], COMPLETED
        )
