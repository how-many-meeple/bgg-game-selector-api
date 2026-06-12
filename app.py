import base64
import binascii
import functools
import json
import logging

import requests
import validators
from flask import Flask, Response, request, jsonify
from flask_cors import CORS
from werkzeug.routing import BaseConverter


class Base64Converter(BaseConverter):
    regex = r"[^/].*?"


from boardgame.board_game import BoardGameFactory, BoardGameUserNotFoundError
from boardgame.filter import Filter
from boardgame.filter_processor import FilterProcessor
from boardgame.recommendation_engine import RecommendationService
from boardgame.vector_generation import GameVectorGenerator, TasteVectorBuilder
from config import Config

logging.basicConfig(
    level=logging.INFO, format="%(asctime)s - %(name)s - %(levelname)s - %(message)s"
)
log = logging.getLogger(__name__)

Config.validate()

application = Flask(__name__)
app = application
app.url_map.converters["b64"] = Base64Converter
CORS(app)

game_cache = BoardGameFactory.create_game_cache()
recommendation_service = RecommendationService(
    BoardGameFactory.create_vector_store(), game_cache
)

log.info(f"Starting BGG Game Selector API with {Config.CACHE_BACKEND} cache backend")


def with_filter(f):
    """Inject a FilterProcessor built from Bgg-Filter-* request headers."""

    @functools.wraps(f)
    def wrapper(*args, **kwargs):
        game_filter = FilterProcessor(Filter.create_filter_chain(request.headers))
        return f(*args, game_filter=game_filter, **kwargs)

    return wrapper


@app.route("/collection/<string:username>")
@with_filter
def show_games_in_collection(username, game_filter):
    selector = BoardGameFactory.create_player_selector(username, request.headers)
    try:
        games = selector.get_games_matching_filter(game_filter)
        return json.dumps(games)
    except BoardGameUserNotFoundError as error:
        return error.message, 404


@app.route("/geeklist/<geek_list>")
@with_filter
def show_games_in_list(geek_list, game_filter):
    selector = BoardGameFactory.create_list_selector(geek_list, request.headers)
    try:
        games = selector.get_games_matching_filter(game_filter)
        return json.dumps(games)
    except BoardGameUserNotFoundError as error:
        return error.message, 404


@app.route("/cors-proxy/<b64:url>")
def cors_proxy(url: str):
    stripped = url[1:]  # strip leading underscore prefix added by HMM frontend
    stripped += "=" * (-len(stripped) % 4)  # restore base64url padding
    try:
        decoded_url = base64.urlsafe_b64decode(stripped).decode("utf-8")
        if not validators.url(decoded_url):
            return "Not a valid URL to proxy", 400
        proxied = requests.get(decoded_url, params=request.args)
        response = Response(
            proxied.content,
            content_type=proxied.headers["content-type"],
            status=proxied.status_code,
        )
        # BGG image URLs are content-addressed — safe to cache permanently in the browser
        response.headers["Cache-Control"] = "public, max-age=31536000, immutable"
        return response
    except binascii.Error:
        return "Unable to decode requested proxy item", 400


@app.route("/search/<string:game_name>")
def search_for_game(game_name):
    search = BoardGameFactory.create_search()
    return json.dumps([game.data() for game in search.search_for_game(game_name)])


@app.route("/health")
def health():
    return json.dumps({"status": "ok", "cache_backend": Config.CACHE_BACKEND})


@app.route("/recommendations/from-games", methods=["POST"])
@with_filter
def recommendations_from_games(game_filter):
    try:
        data = request.get_json()

        if not data or "game_ids" not in data:
            return jsonify({"error": "Missing required field: game_ids"}), 400

        game_ids = data["game_ids"]
        if not isinstance(game_ids, list) or not game_ids:
            return jsonify({"error": "game_ids must be a non-empty list"}), 400

        limit = data.get("limit", 10)
        exclude_ids = data.get("exclude_ids", [])

        games_data = []
        for game_id in game_ids:
            game = game_cache.load(game_id)
            if game:
                games_data.append(game.data())
            else:
                log.warning(f"Game {game_id} not found in cache")

        if not games_data:
            return (
                jsonify({"error": "None of the provided game IDs were found in cache"}),
                404,
            )

        taste_vector = TasteVectorBuilder.build(games_data)

        recommendations = recommendation_service.recommend_from_taste_vector(
            taste_vector=taste_vector,
            limit=limit,
            exclude_ids=exclude_ids + game_ids,
            game_filter=game_filter,
        )

        return jsonify(
            {
                "recommendations": [rec.to_dict() for rec in recommendations],
                "input_games_count": len(games_data),
                "taste_vector_dimensions": len(taste_vector),
            }
        )

    except Exception as e:
        log.error(f"Error generating recommendations: {e}", exc_info=True)
        return jsonify({"error": "Internal server error"}), 500


@app.route("/recommendations/schema", methods=["GET"])
def recommendations_schema():
    schema = GameVectorGenerator.get_schema()
    return jsonify(schema)


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000, debug=True)
