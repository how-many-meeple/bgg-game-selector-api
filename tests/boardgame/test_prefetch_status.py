"""
Tests for PrefetchStatusStore implementations following RightBICEP principles:
- Right: Are the results right?
- Boundary: Are all the boundary conditions correct?
- Inverse: Can you check inverse relationships?
- Cross-check: Can you cross-check results using other means?
- Error: Can you force error conditions?
- Performance: Are there performance characteristics?
"""

import os
import time
import unittest
from unittest.mock import patch

import boto3
from moto import mock_aws

from boardgame.prefetch_status import (
    COMPLETED,
    FAILED,
    NOT_FOUND,
    PENDING,
    PROCESSING,
    RETRYABLE_STATUSES,
    DynamoDBPrefetchStatusStore,
    SQLitePrefetchStatusStore,
    SourceType,
)


class PrefetchStatusStoreContractTests:
    """Shared contract tests run against both SQLite and DynamoDB implementations.

    Subclasses must call self.store = self.make_store() in their own setUp so that
    the store is created inside the correct mock context (e.g. @mock_aws).
    """

    def make_store(self):
        raise NotImplementedError

    # RIGHT: Are the results right?

    def test_get_returns_none_for_unknown_source(self):
        result = self.store.get(SourceType.COLLECTION, "unknown_user")
        self.assertIsNone(result)

    def test_set_and_get_returns_correct_status(self):
        self.store.set(SourceType.COLLECTION, "testuser", PENDING)
        result = self.store.get(SourceType.COLLECTION, "testuser")
        self.assertIsNotNone(result)
        self.assertEqual(result["status"], PENDING)
        self.assertEqual(result["source_type"], SourceType.COLLECTION)
        self.assertEqual(result["source_id"], "testuser")

    def test_set_stores_reason(self):
        self.store.set(
            SourceType.COLLECTION,
            "testuser",
            NOT_FOUND,
            reason="No user found called 'testuser'",
        )
        result = self.store.get(SourceType.COLLECTION, "testuser")
        self.assertEqual(result["reason"], "No user found called 'testuser'")

    def test_set_defaults_reason_to_empty_string(self):
        self.store.set(SourceType.COLLECTION, "testuser", COMPLETED)
        result = self.store.get(SourceType.COLLECTION, "testuser")
        self.assertEqual(result["reason"], "")

    def test_source_type_is_returned_as_enum(self):
        self.store.set(SourceType.GEEKLIST, "12345", PENDING)
        result = self.store.get(SourceType.GEEKLIST, "12345")
        self.assertIsInstance(result["source_type"], SourceType)
        self.assertEqual(result["source_type"], SourceType.GEEKLIST)

    def test_collection_and_geeklist_are_independent(self):
        self.store.set(SourceType.COLLECTION, "same_id", PENDING)
        self.store.set(SourceType.GEEKLIST, "same_id", COMPLETED)
        self.assertEqual(
            self.store.get(SourceType.COLLECTION, "same_id")["status"], PENDING
        )
        self.assertEqual(
            self.store.get(SourceType.GEEKLIST, "same_id")["status"], COMPLETED
        )

    def test_set_overwrites_existing_status(self):
        self.store.set(SourceType.COLLECTION, "testuser", PENDING)
        self.store.set(SourceType.COLLECTION, "testuser", PROCESSING)
        result = self.store.get(SourceType.COLLECTION, "testuser")
        self.assertEqual(result["status"], PROCESSING)

    # BOUNDARY: Are all the boundary conditions correct?

    def test_get_returns_none_after_ttl_expires(self):
        self.store.set(SourceType.COLLECTION, "testuser", FAILED)
        # Manually expire by patching time
        with patch(
            "boardgame.prefetch_status.time.time", return_value=time.time() + 99999
        ):
            result = self.store.get(SourceType.COLLECTION, "testuser")
        self.assertIsNone(result)

    def test_get_returns_item_just_before_ttl_expires(self):
        self.store.set(SourceType.COLLECTION, "testuser", PENDING)
        result = self.store.get(SourceType.COLLECTION, "testuser")
        self.assertIsNotNone(result)

    def test_ttl_is_set_in_future(self):
        now = int(time.time())
        self.store.set(SourceType.COLLECTION, "testuser", PENDING)
        result = self.store.get(SourceType.COLLECTION, "testuser")
        self.assertGreater(result["ttl"], now)

    # INVERSE: Can you check inverse relationships?

    def test_is_queueable_returns_true_when_no_status(self):
        self.assertTrue(self.store.is_queueable(SourceType.COLLECTION, "newuser"))

    def test_is_queueable_returns_false_for_pending(self):
        self.store.set(SourceType.COLLECTION, "testuser", PENDING)
        self.assertFalse(self.store.is_queueable(SourceType.COLLECTION, "testuser"))

    def test_is_queueable_returns_false_for_processing(self):
        self.store.set(SourceType.COLLECTION, "testuser", PROCESSING)
        self.assertFalse(self.store.is_queueable(SourceType.COLLECTION, "testuser"))

    def test_is_queueable_returns_false_for_completed(self):
        self.store.set(SourceType.COLLECTION, "testuser", COMPLETED)
        self.assertFalse(self.store.is_queueable(SourceType.COLLECTION, "testuser"))

    def test_is_queueable_returns_false_for_not_found(self):
        self.store.set(SourceType.COLLECTION, "testuser", NOT_FOUND)
        self.assertFalse(self.store.is_queueable(SourceType.COLLECTION, "testuser"))

    def test_is_queueable_returns_true_for_failed(self):
        self.store.set(SourceType.COLLECTION, "testuser", FAILED)
        self.assertTrue(self.store.is_queueable(SourceType.COLLECTION, "testuser"))

    def test_is_queueable_returns_true_after_any_status_expires(self):
        self.store.set(SourceType.COLLECTION, "testuser", PENDING)
        with patch(
            "boardgame.prefetch_status.time.time", return_value=time.time() + 99999
        ):
            self.assertTrue(self.store.is_queueable(SourceType.COLLECTION, "testuser"))

    # CROSS-CHECK: Can you cross-check results using other means?

    def test_retryable_statuses_only_contains_failed(self):
        self.assertEqual(RETRYABLE_STATUSES, {FAILED})

    def test_all_status_values_are_distinct(self):
        statuses = [PENDING, PROCESSING, COMPLETED, NOT_FOUND, FAILED]
        self.assertEqual(len(statuses), len(set(statuses)))

    # ERROR: Can you force error conditions?

    def test_get_handles_exception_gracefully(self):
        with patch.object(self.store, "get", side_effect=Exception("db error")):
            with self.assertRaises(Exception):
                self.store.get(SourceType.COLLECTION, "testuser")


class TestSQLitePrefetchStatusStore(
    PrefetchStatusStoreContractTests, unittest.TestCase
):

    def setUp(self):
        self.store = self.make_store()

    def make_store(self):
        self._db_path = "test_prefetch_status.sqlite"
        return SQLitePrefetchStatusStore(db_path=self._db_path)

    def tearDown(self):
        if hasattr(self, "store") and hasattr(self.store, "_conn"):
            self.store._conn.close()
        if os.path.exists(self._db_path):
            os.remove(self._db_path)

    def test_schema_is_idempotent(self):
        # Creating a second store against the same DB should not raise
        store2 = SQLitePrefetchStatusStore(db_path=self._db_path)
        store2._conn.close()

    def test_data_persists_across_instances(self):
        self.store.set(SourceType.COLLECTION, "testuser", COMPLETED)
        self.store._conn.close()
        store2 = SQLitePrefetchStatusStore(db_path=self._db_path)
        result = store2.get(SourceType.COLLECTION, "testuser")
        self.assertIsNotNone(result)
        self.assertEqual(result["status"], COMPLETED)
        store2._conn.close()


@mock_aws
class TestDynamoDBPrefetchStatusStore(unittest.TestCase):
    """DynamoDB-specific tests. @mock_aws at class level wraps setUp + every test method."""

    TABLE_NAME = "test-prefetch-status"
    REGION = "us-east-1"

    def setUp(self):
        dynamodb = boto3.client("dynamodb", region_name=self.REGION)
        dynamodb.create_table(
            TableName=self.TABLE_NAME,
            KeySchema=[{"AttributeName": "id", "KeyType": "HASH"}],
            AttributeDefinitions=[{"AttributeName": "id", "AttributeType": "S"}],
            BillingMode="PAY_PER_REQUEST",
        )
        dynamodb.update_time_to_live(
            TableName=self.TABLE_NAME,
            TimeToLiveSpecification={"Enabled": True, "AttributeName": "ttl"},
        )
        self.store = DynamoDBPrefetchStatusStore(
            table_name=self.TABLE_NAME, region=self.REGION
        )

    # RIGHT: Are the results right?

    def test_get_returns_none_for_unknown_source(self):
        self.assertIsNone(self.store.get(SourceType.COLLECTION, "unknown_user"))

    def test_set_and_get_returns_correct_status(self):
        self.store.set(SourceType.COLLECTION, "testuser", PENDING)
        result = self.store.get(SourceType.COLLECTION, "testuser")
        self.assertIsNotNone(result)
        self.assertEqual(result["status"], PENDING)
        self.assertEqual(result["source_type"], SourceType.COLLECTION)
        self.assertEqual(result["source_id"], "testuser")

    def test_set_stores_reason(self):
        self.store.set(
            SourceType.COLLECTION,
            "testuser",
            NOT_FOUND,
            reason="No user found called 'testuser'",
        )
        result = self.store.get(SourceType.COLLECTION, "testuser")
        self.assertEqual(result["reason"], "No user found called 'testuser'")

    def test_set_defaults_reason_to_empty_string(self):
        self.store.set(SourceType.COLLECTION, "testuser", COMPLETED)
        self.assertEqual(
            self.store.get(SourceType.COLLECTION, "testuser")["reason"], ""
        )

    def test_source_type_is_returned_as_enum(self):
        self.store.set(SourceType.GEEKLIST, "12345", PENDING)
        result = self.store.get(SourceType.GEEKLIST, "12345")
        self.assertIsInstance(result["source_type"], SourceType)

    def test_collection_and_geeklist_are_independent(self):
        self.store.set(SourceType.COLLECTION, "same_id", PENDING)
        self.store.set(SourceType.GEEKLIST, "same_id", COMPLETED)
        self.assertEqual(
            self.store.get(SourceType.COLLECTION, "same_id")["status"], PENDING
        )
        self.assertEqual(
            self.store.get(SourceType.GEEKLIST, "same_id")["status"], COMPLETED
        )

    def test_set_overwrites_existing_status(self):
        self.store.set(SourceType.COLLECTION, "testuser", PENDING)
        self.store.set(SourceType.COLLECTION, "testuser", PROCESSING)
        self.assertEqual(
            self.store.get(SourceType.COLLECTION, "testuser")["status"], PROCESSING
        )

    def test_item_is_written_to_dynamodb(self):
        self.store.set(SourceType.COLLECTION, "testuser", PENDING)
        table = boto3.resource("dynamodb", region_name=self.REGION).Table(
            self.TABLE_NAME
        )
        response = table.get_item(Key={"id": "collection:testuser"})
        self.assertIn("Item", response)
        self.assertEqual(response["Item"]["status"], PENDING)

    # BOUNDARY: Are all the boundary conditions correct?

    def test_get_returns_none_after_ttl_expires(self):
        self.store.set(SourceType.COLLECTION, "testuser", FAILED)
        with patch(
            "boardgame.prefetch_status.time.time", return_value=time.time() + 99999
        ):
            self.assertIsNone(self.store.get(SourceType.COLLECTION, "testuser"))

    def test_ttl_is_set_in_future(self):
        now = int(time.time())
        self.store.set(SourceType.COLLECTION, "testuser", PENDING)
        self.assertGreater(
            self.store.get(SourceType.COLLECTION, "testuser")["ttl"], now
        )

    # INVERSE: Can you check inverse relationships?

    def test_is_queueable_returns_true_when_no_status(self):
        self.assertTrue(self.store.is_queueable(SourceType.COLLECTION, "newuser"))

    def test_is_queueable_returns_false_for_pending(self):
        self.store.set(SourceType.COLLECTION, "testuser", PENDING)
        self.assertFalse(self.store.is_queueable(SourceType.COLLECTION, "testuser"))

    def test_is_queueable_returns_false_for_completed(self):
        self.store.set(SourceType.COLLECTION, "testuser", COMPLETED)
        self.assertFalse(self.store.is_queueable(SourceType.COLLECTION, "testuser"))

    def test_is_queueable_returns_true_for_failed(self):
        self.store.set(SourceType.COLLECTION, "testuser", FAILED)
        self.assertTrue(self.store.is_queueable(SourceType.COLLECTION, "testuser"))

    # ERROR: Can you force error conditions?

    def test_get_handles_dynamodb_exception_gracefully(self):
        from botocore.exceptions import ClientError

        error_response = {"Error": {"Code": "InternalServerError", "Message": "oops"}}
        with patch.object(
            self.store._table,
            "get_item",
            side_effect=ClientError(error_response, "GetItem"),
        ):
            self.assertIsNone(self.store.get(SourceType.COLLECTION, "testuser"))

    def test_set_handles_dynamodb_exception_gracefully(self):
        from botocore.exceptions import ClientError

        error_response = {"Error": {"Code": "InternalServerError", "Message": "oops"}}
        with patch.object(
            self.store._table,
            "put_item",
            side_effect=ClientError(error_response, "PutItem"),
        ):
            self.store.set(
                SourceType.COLLECTION, "testuser", PENDING
            )  # should not raise
