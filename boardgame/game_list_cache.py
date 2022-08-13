import json
import time
from typing import Optional, List

import boto3
from boardgamegeek import BGGClient, BGGClientLegacy
from boardgamegeek.objects.games import BoardGame
from botocore.exceptions import ClientError


class GameListCache:
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
                        'AttributeName': 'id',  # geeklist id or player name
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

    def geeklist_games(self, geek_list: str, client: BGGClientLegacy):
        game_id_list = self.load(geek_list)
        if not game_id_list:
            game_id_list = [list_obj.object.id for list_obj in client.geeklist(geek_list).items]
            self.save(geek_list, game_id_list)
        return game_id_list

    def collection_games(self, collection: str, client: BGGClient):
        game_id_list = self.load(collection)
        if not game_id_list:
            game_list = client.collection(collection, own=True).items
            game_id_list = [game.id for game in game_list]
            self.save(collection, game_id_list)
        return game_id_list

    def save(self, player_or_geeklist: str, games_id_list: List[int]):
        table = self._dynamo_resource.Table(self._table_name)
        table.update_item(
            Key={
                    'id': player_or_geeklist
                },
            UpdateExpression='SET games_list = :games_list, expiration = :expiry_date',
            ExpressionAttributeValues={
                ':games_list': games_id_list,
                ':expiry_date': int(int(time.time()) + self.cache_length)
            }
        )

    def load(self, player_or_geeklist: str) -> List[int]:
        table = self._dynamo_resource.Table(self._table_name)
        response = table.get_item(
            Key={
                'id': str(player_or_geeklist)
            })
        decimal_games_list = response['Item'].get("games_list", []) if response.get('Item') else []
        return [int(id) for id in decimal_games_list]
