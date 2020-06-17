import json
import logging

from flask import Flask, request
from flask_cors import CORS
from flask_apscheduler import APScheduler

from boardgame.board_game import BoardGameFactory, BoardGameUserNotFoundError
from boardgame.filter import Filter
from boardgame.filter_processor import FilterProcessor


class Config(object):
    SCHEDULER_API_ENABLED = True


FORMAT = '[%(asctime)s] %(levelname)s in %(module)s: %(message)s'
logging.basicConfig(format=FORMAT)

application = Flask(__name__)
app = application  # aliased application for convenience
CORS(app)
scheduler = APScheduler()

log = logging.getLogger()
log.setLevel(logging.INFO)

app.config.from_object(Config())

scheduler.init_app(app)
scheduler.start()


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


@app.route("/last_cache_refresh")
def last_cache_refresh():
    game_cache = BoardGameFactory.create_game_cache()
    return json.dumps({'refresh-date': game_cache.last_refresh()})


@scheduler.task('cron', id='refresh_games', hour="*/2")
def refresh_games():
    refresher = BoardGameFactory.create_game_refresher()
    refresher.refresh_stale_games()