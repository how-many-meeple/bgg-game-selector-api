import json
import logging

from flask import Flask, request, jsonify
from flask_cors import CORS

from boardgame.board_game import BoardGameFactory, BoardGameUserNotFoundError
from boardgame.filter import Filter
from boardgame.filter_processor import FilterProcessor
from boardgame.recommendation_engine import RecommendationService
from boardgame.vector_generation import GameVectorGenerator, TasteVectorBuilder
from config import Config

# Configure logging
logging.basicConfig(
    level=logging.INFO, format="%(asctime)s - %(name)s - %(levelname)s - %(message)s"
)
log = logging.getLogger(__name__)

# Validate configuration
Config.validate()

application = Flask(__name__)
app = application  # aliased application for convenience
CORS(app)

# Initialize shared services
game_cache = BoardGameFactory.create_game_cache()
recommendation_service = RecommendationService(BoardGameFactory.create_vector_store())

log.info(f"Starting BGG Game Selector API with {Config.CACHE_BACKEND} cache backend")


@app.route("/collection/<string:username>")
def show_games_in_collection(username):
    selector = BoardGameFactory.create_player_selector(username, request.headers)
    try:
        game_filter = FilterProcessor(Filter.create_filter_chain(request.headers))
        games = selector.get_games_matching_filter(game_filter)

        return json.dumps(games)
    except BoardGameUserNotFoundError as error:
        return error.message, 404


@app.route("/geeklist/<geek_list>")
def show_games_in_list(geek_list):
    selector = BoardGameFactory.create_list_selector(geek_list, request.headers)
    try:
        game_filter = FilterProcessor(Filter.create_filter_chain(request.headers))
        games = selector.get_games_matching_filter(game_filter)

        return json.dumps(games)
    except BoardGameUserNotFoundError as error:
        return error.message, 404


@app.route("/search/<string:game_name>")
def search_for_game(game_name):
    search = BoardGameFactory.create_search()

    return json.dumps([game.data() for game in search.search_for_game(game_name)])


@app.route("/health")
def health():
    return json.dumps({"status": "ok", "cache_backend": Config.CACHE_BACKEND})


@app.route("/recommendations/from-games", methods=["POST"])
def recommendations_from_games():
    """
    Generate game recommendations based on a list of input game IDs.

    Request body:
        {
            "game_ids": [1, 2, 3],
            "limit": 10,  # optional, default 10
            "exclude_ids": [4, 5],  # optional
            "player_count": 4,  # optional
            "min_playtime": 30,  # optional
            "max_playtime": 120  # optional
        }

    Response:
        {
            "recommendations": [
                {"game_id": 123, "name": "Game Name", "similarity_score": 0.85},
                ...
            ]
        }
    """
    try:
        data = request.get_json()

        if not data or "game_ids" not in data:
            return jsonify({"error": "Missing required field: game_ids"}), 400

        game_ids = data["game_ids"]
        if not isinstance(game_ids, list) or not game_ids:
            return jsonify({"error": "game_ids must be a non-empty list"}), 400

        limit = data.get("limit", 10)
        exclude_ids = data.get("exclude_ids", [])
        player_count = data.get("player_count")
        min_playtime = data.get("min_playtime")
        max_playtime = data.get("max_playtime")

        # Load game data from cache
        games_data = []
        for game_id in game_ids:
            game = game_cache.load(game_id)
            if game:
                games_data.append(game.data())
            else:
                log.warning(f"Game {game_id} not found in cache")

        if not games_data:
            return (
                jsonify(
                    {"error": "None of the provided game IDs were found in cache"}
                ),
                404,
            )

        # Build taste vector from games
        taste_vector = TasteVectorBuilder.build(games_data)

        # Get recommendations
        recommendations = recommendation_service.recommend_from_game_vectors(
            game_vectors=[taste_vector],  # Already aggregated
            limit=limit,
            exclude_ids=exclude_ids + game_ids,  # Also exclude input games
            player_count=player_count,
            min_playtime=min_playtime,
            max_playtime=max_playtime,
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
    """Return the vector schema documentation"""
    schema = GameVectorGenerator.get_schema()
    return jsonify(schema)


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000, debug=True)
