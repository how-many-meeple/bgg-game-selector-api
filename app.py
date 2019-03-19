import json

from flask import Flask, request
from flask_cors import CORS

from boardgame.board_game import BoardGameFactory, BoardGameUserNotFoundError
from boardgame.filter import Filter
from boardgame.filter_processor import FilterProcessor

application = Flask(__name__)
app = application  # aliased application for convenience
CORS(app)


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
