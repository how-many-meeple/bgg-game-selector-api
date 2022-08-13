from typing import List, Optional

from boardgamegeek.objects.games import BaseGame
from werkzeug.datastructures import Headers


class FieldReduction(object):
    _BggFieldReductionHeader = "Bgg-Field-Whitelist"

    def __init__(self, fields: Optional[dict]):
        self.fields = fields

    def clean_response(self, response: List[BaseGame]) -> List[dict]:
        return [self.remove_unwanted(item.data()) for item in response]

    def remove_unwanted(self, data: dict) -> dict:
        if not self.fields:
            return data
        return {key: data[key] for key in self.fields if data.get(key)}

    @staticmethod
    def create_field_reduction(headers: Headers) -> 'FieldReduction':
        fields = headers.get(FieldReduction._BggFieldReductionHeader)
        return FieldReduction(fields.split(",") if fields else None)
