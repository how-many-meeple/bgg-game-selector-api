import base64
import binascii
import json
import validators

import flask
import requests
from flask import Flask, stream_with_context, Response
from flask_cors import CORS

from boardgame.board_game import BoardGameFactory, BoardGameUserNotFoundError
from boardgame.filter import Filter
from boardgame.filter_processor import FilterProcessor

application = Flask(__name__)
app = application  # aliased application for convenience
CORS(app)


@app.route("/collection/<string:username>")
def show_games_in_collection(username):
    selector = BoardGameFactory.create_player_selector(username, flask.request.headers)
    try:
        game_filter = FilterProcessor(Filter.create_filter_chain(flask.request.headers))
        games = selector.get_games_matching_filter(game_filter)

        return json.dumps(games)
    except BoardGameUserNotFoundError as error:
        return error.message, 404


@app.route("/geeklist/<geek_list>")
def show_games_in_list(geek_list):
    selector = BoardGameFactory.create_list_selector(geek_list, flask.request.headers)
    try:
        game_filter = FilterProcessor(Filter.create_filter_chain(flask.request.headers))
        games = selector.get_games_matching_filter(game_filter)

        return json.dumps(games)
    except BoardGameUserNotFoundError as error:
        return error.message, 404


def prefix_strip(encoded_url):
    return encoded_url[1:]


@app.route("/cors-proxy/<url>")
def cors_proxy(url: str):
    stripped_url = prefix_strip(url)
    try:
        decoded_url = base64.b64decode(stripped_url).decode("utf-8")
        if not validators.url(decoded_url):
            return "Not a valid URL to proxy", 400
        request = requests.get(decoded_url, stream=True, params=flask.request.args)
        response = Response(stream_with_context(request.iter_content()),
                            content_type=request.headers['content-type'],
                            status=request.status_code)
    except binascii.Error as e:
        return "Unable to decode requested proxy item", 400
    return response


@app.route("/search/<string:game_name>")
def search_for_game(game_name):
    search = BoardGameFactory.create_search()

    return json.dumps([game.data() for game in search.search_for_game(game_name)])
