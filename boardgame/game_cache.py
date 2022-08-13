import json
import time
from typing import Optional

import boto3
from boardgamegeek.objects.games import BoardGame
from botocore.exceptions import ClientError


class GameCache:
    def __init__(self, cache_length, table_name):
        self._dynamo_resource = boto3.resource('dynamodb')
        self._table_name = table_name
        self.cache_length = cache_length
        self.prepare_schema(table_name)

    def prepare_schema(self, table_name):
        # Create the DynamoDB table.
        try:
            table = self._dynamo_resource.create_table(
                TableName=table_name,
                KeySchema=[
                    {
                        'AttributeName': 'id',
                        'KeyType': 'HASH'
                    }
                ],
                AttributeDefinitions=[
                    {
                        'AttributeName': 'id',
                        'AttributeType': 'S'
                    }
                ],
                BillingMode='PAY_PER_REQUEST'
            )
            table.wait_until_exists()
            """Enable TTL, if not already enabled"""

            self._dynamo_resource.meta.client.update_time_to_live(
                TableName=self._table_name,
                TimeToLiveSpecification={'AttributeName': 'expiration', 'Enabled': True},
            )
        except self._dynamo_resource.meta.client.exceptions.ResourceInUseException:
            pass
        except ClientError as e:
            if e.response['Error']['Code'] != 'ValidationException':
                raise

    def save(self, game: BoardGame):
        table = self._dynamo_resource.Table(self._table_name)
        table.update_item(
            Key={
                    'id': str(game.id)
                },
            UpdateExpression='SET game_data = :game_data, expiration = :expiry_date',
            ExpressionAttributeValues={
                ':game_data': json.dumps(game.data()),
                ':expiry_date': int(int(time.time()) + self.cache_length)
            }
        )

    def load(self, game_id: int) -> Optional[BoardGame]:
        table = self._dynamo_resource.Table(self._table_name)
        response = table.get_item(
            Key={
                'id': str(game_id)
            })
        return BoardGame(json.loads(response['Item'].get("game_data"))) if response.get('Item') else None
