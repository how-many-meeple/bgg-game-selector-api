import json

from flask import Flask, request
from flask_cors import CORS

from boardgame.BoardGame import BoardGameFactory, BoardGameUserNotFoundError
from boardgame.Filter import Filter
from boardgame.FilterProcessor import FilterProcessor

application = Flask(__name__)
app = application  # aliased application for convenience
CORS(app)


@app.route("/collection/<string:username>")
def show_games_in_collection(username):
    try:
        selector = BoardGameFactory.create_selector(username)
        gfilter = FilterProcessor(Filter.create_filter_chain(request.headers))
        games = selector.get_games_matching_filter(gfilter)

        return json.dumps([game.data() for game in games])
    except BoardGameUserNotFoundError as error:
        return error.message, 404


@app.route("/search/<string:game_name>")
def search_for_game(game_name):
    search = BoardGameFactory.create_search()

    return json.dumps([game.data() for game in search.search_for_game(game_name)])
