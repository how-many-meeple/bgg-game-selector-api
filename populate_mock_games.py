#!/usr/bin/env python3
"""
Populate game cache with mock data for testing recommendations locally.

This script creates realistic BoardGame objects and saves them to the cache,
which automatically generates vectors via VectorSync.

Usage:
    python populate_mock_games.py [--count 50]
"""

import argparse
import logging
import sys

from boardgamegeek.objects.games import BoardGame

from boardgame.board_game import BoardGameFactory
from boardgame.vector_sync import VectorSync
from config import Config

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
)
log = logging.getLogger(__name__)

# Realistic mock game data based on popular BGG games
MOCK_GAMES = [
    {
        "id": 174430,
        "name": "Gloomhaven",
        "year": 2017,
        "image": "https://cf.geekdo-images.com/sZYp_3BTDGjh2unaZfZmuA__original/img/FyLNR8OjpPXxHJiG1lLLp0C5eOs=/0x0/filters:format(jpeg)/pic2437871.jpg",
        "thumbnail": "https://cf.geekdo-images.com/sZYp_3BTDGjh2unaZfZmuA__thumb/img/SEq-Z0K4tvKn0A2Thyynd2S2IjM=/fit-in/200x150/filters:strip_icc()/pic2437871.jpg",
        "minplayers": 1,
        "maxplayers": 4,
        "playingtime": 120,
        "minplaytime": 60,
        "maxplaytime": 120,
        "minage": 14,
        "users_rated": 85423,
        "average": 8.7,
        "bayesaverage": 8.3,
        "stddev": 1.5,
        "median": 0,
        "owned": 156789,
        "trading": 1234,
        "wanting": 5678,
        "wishing": 23456,
        "numcomments": 34567,
        "numweights": 12345,
        "averageweight": 3.86,
        "boardgamecategory": [
            "Adventure",
            "Exploration",
            "Fantasy",
            "Fighting",
            "Miniatures",
        ],
        "boardgamemechanic": [
            "Action Queue",
            "Campaign / Battle Card Driven",
            "Cooperative Game",
            "Grid Movement",
            "Hand Management",
            "Modular Board",
            "Role Playing",
            "Simultaneous Action Selection",
            "Variable Player Powers",
        ],
        "boardgamedesigner": ["Isaac Childres"],
        "boardgameartist": ["Alexandr Elichev", "Josh T. McDowell", "Alvaro Nebot"],
        "boardgamepublisher": ["Cephalofair Games"],
    },
    {
        "id": 167791,
        "name": "Terraforming Mars",
        "year": 2016,
        "minplayers": 1,
        "maxplayers": 5,
        "playingtime": 120,
        "minplaytime": 90,
        "maxplaytime": 120,
        "minage": 12,
        "users_rated": 98765,
        "average": 8.4,
        "bayesaverage": 8.1,
        "stddev": 1.3,
        "owned": 178901,
        "averageweight": 3.25,
        "boardgamecategory": [
            "Economic",
            "Environmental",
            "Industry / Manufacturing",
            "Science Fiction",
            "Space Exploration",
            "Territory Building",
        ],
        "boardgamemechanic": [
            "Card Drafting",
            "Hand Management",
            "Set Collection",
            "Tile Placement",
            "Turn Order: Progressive",
        ],
        "boardgamedesigner": ["Jacob Fryxelius"],
        "boardgamepublisher": ["FryxGames"],
    },
    {
        "id": 220308,
        "name": "Gaia Project",
        "year": 2017,
        "minplayers": 1,
        "maxplayers": 4,
        "playingtime": 150,
        "minplaytime": 60,
        "maxplaytime": 150,
        "minage": 14,
        "users_rated": 45678,
        "average": 8.4,
        "bayesaverage": 8.0,
        "owned": 67890,
        "averageweight": 4.36,
        "boardgamecategory": [
            "Civilization",
            "Economic",
            "Science Fiction",
            "Space Exploration",
            "Territory Building",
        ],
        "boardgamemechanic": [
            "Action Points",
            "Grid Movement",
            "Income",
            "Tech Trees / Tech Tracks",
            "Turn Order: Pass Order",
            "Variable Player Powers",
        ],
        "boardgamedesigner": ["Jens Drögemüller", "Helge Ostertag"],
        "boardgamepublisher": ["Feuerland Spiele"],
    },
    {
        "id": 266192,
        "name": "Wingspan",
        "year": 2019,
        "minplayers": 1,
        "maxplayers": 5,
        "playingtime": 70,
        "minplaytime": 40,
        "maxplaytime": 70,
        "minage": 10,
        "users_rated": 112345,
        "average": 8.0,
        "bayesaverage": 7.8,
        "owned": 189012,
        "averageweight": 2.44,
        "boardgamecategory": ["Animals", "Card Game", "Educational"],
        "boardgamemechanic": [
            "Card Drafting",
            "Dice Rolling",
            "End Game Bonuses",
            "Hand Management",
            "Set Collection",
        ],
        "boardgamedesigner": ["Elizabeth Hargrave"],
        "boardgamepublisher": ["Stonemaier Games"],
    },
    {
        "id": 182028,
        "name": "Through the Ages: A New Story of Civilization",
        "year": 2015,
        "minplayers": 2,
        "maxplayers": 4,
        "playingtime": 240,
        "minplaytime": 120,
        "maxplaytime": 240,
        "minage": 14,
        "users_rated": 34567,
        "average": 8.6,
        "bayesaverage": 8.1,
        "owned": 56789,
        "averageweight": 4.42,
        "boardgamecategory": ["Card Game", "Civilization", "Economic", "Political"],
        "boardgamemechanic": [
            "Action Points",
            "Card Drafting",
            "Hand Management",
            "Income",
            "Tech Trees / Tech Tracks",
            "Variable Player Powers",
        ],
        "boardgamedesigner": ["Vlaada Chvátil"],
        "boardgamepublisher": ["Czech Games Edition"],
    },
    {
        "id": 169786,
        "name": "Scythe",
        "year": 2016,
        "minplayers": 1,
        "maxplayers": 5,
        "playingtime": 115,
        "minplaytime": 90,
        "maxplaytime": 115,
        "minage": 14,
        "users_rated": 98765,
        "average": 8.1,
        "bayesaverage": 7.9,
        "owned": 145678,
        "averageweight": 3.38,
        "boardgamecategory": [
            "Economic",
            "Fighting",
            "Science Fiction",
            "Territory Building",
        ],
        "boardgamemechanic": [
            "Area Majority / Influence",
            "End Game Bonuses",
            "Grid Movement",
            "Simultaneous Action Selection",
            "Variable Player Powers",
        ],
        "boardgamedesigner": ["Jamey Stegmaier"],
        "boardgamepublisher": ["Stonemaier Games"],
    },
    {
        "id": 284083,
        "name": "The Crew: The Quest for Planet Nine",
        "year": 2019,
        "minplayers": 2,
        "maxplayers": 5,
        "playingtime": 20,
        "minplaytime": 20,
        "maxplaytime": 20,
        "minage": 10,
        "users_rated": 67890,
        "average": 7.7,
        "bayesaverage": 7.5,
        "owned": 89012,
        "averageweight": 2.03,
        "boardgamecategory": ["Card Game", "Science Fiction", "Space Exploration"],
        "boardgamemechanic": [
            "Cooperative Game",
            "Scenario / Mission / Campaign Game",
            "Trick-taking",
        ],
        "boardgamedesigner": ["Thomas Sing"],
        "boardgamepublisher": ["KOSMOS"],
    },
    {
        "id": 224517,
        "name": "Brass: Birmingham",
        "year": 2018,
        "minplayers": 2,
        "maxplayers": 4,
        "playingtime": 120,
        "minplaytime": 60,
        "maxplaytime": 120,
        "minage": 14,
        "users_rated": 56789,
        "average": 8.6,
        "bayesaverage": 8.2,
        "owned": 78901,
        "averageweight": 3.91,
        "boardgamecategory": [
            "Economic",
            "Industry / Manufacturing",
            "Post-Napoleonic",
            "Transportation",
        ],
        "boardgamemechanic": [
            "Hand Management",
            "Income",
            "Loans",
            "Market",
            "Network and Route Building",
            "Turn Order: Pass Order",
        ],
        "boardgamedesigner": ["Martin Wallace", "Gavan Brown", "Matt Tolman"],
        "boardgamepublisher": ["Roxley"],
    },
    {
        "id": 291457,
        "name": "Ark Nova",
        "year": 2021,
        "minplayers": 1,
        "maxplayers": 4,
        "playingtime": 150,
        "minplaytime": 90,
        "maxplaytime": 150,
        "minage": 14,
        "users_rated": 45678,
        "average": 8.5,
        "bayesaverage": 8.1,
        "owned": 67890,
        "averageweight": 3.71,
        "boardgamecategory": ["Animals", "Card Game", "Economic", "Environmental"],
        "boardgamemechanic": [
            "Card Drafting",
            "End Game Bonuses",
            "Hand Management",
            "Income",
            "Set Collection",
            "Tile Placement",
        ],
        "boardgamedesigner": ["Mathias Wigge"],
        "boardgamepublisher": ["Feuerland Spiele"],
    },
    {
        "id": 161936,
        "name": "Pandemic Legacy: Season 1",
        "year": 2015,
        "minplayers": 2,
        "maxplayers": 4,
        "playingtime": 60,
        "minplaytime": 60,
        "maxplaytime": 60,
        "minage": 13,
        "users_rated": 89012,
        "average": 8.6,
        "bayesaverage": 8.2,
        "owned": 123456,
        "averageweight": 2.84,
        "boardgamecategory": ["Medical"],
        "boardgamemechanic": [
            "Action Points",
            "Campaign / Battle Card Driven",
            "Cooperative Game",
            "Hand Management",
            "Legacy Game",
            "Point to Point Movement",
            "Set Collection",
            "Variable Player Powers",
        ],
        "boardgamedesigner": ["Rob Daviau", "Matt Leacock"],
        "boardgamepublisher": ["Z-Man Games"],
    },
    {
        "id": 342942,
        "name": "Ark Nova: Marine Worlds",
        "year": 2023,
        "minplayers": 1,
        "maxplayers": 4,
        "playingtime": 150,
        "minplaytime": 90,
        "maxplaytime": 150,
        "minage": 14,
        "users_rated": 12345,
        "average": 8.7,
        "bayesaverage": 7.8,
        "owned": 34567,
        "averageweight": 3.85,
        "boardgamecategory": [
            "Animals",
            "Card Game",
            "Economic",
            "Environmental",
            "Expansion for Base-game",
        ],
        "boardgamemechanic": [
            "Card Drafting",
            "Hand Management",
            "Set Collection",
            "Tile Placement",
        ],
        "boardgamedesigner": ["Mathias Wigge"],
        "boardgamepublisher": ["Feuerland Spiele"],
    },
    {
        "id": 233078,
        "name": "Twilight Imperium: Fourth Edition",
        "year": 2017,
        "minplayers": 3,
        "maxplayers": 6,
        "playingtime": 480,
        "minplaytime": 240,
        "maxplaytime": 480,
        "minage": 14,
        "users_rated": 34567,
        "average": 8.7,
        "bayesaverage": 8.0,
        "owned": 45678,
        "averageweight": 4.24,
        "boardgamecategory": [
            "Civilization",
            "Economic",
            "Negotiation",
            "Political",
            "Science Fiction",
            "Space Exploration",
            "Wargame",
        ],
        "boardgamemechanic": [
            "Action Queue",
            "Area Majority / Influence",
            "Area Movement",
            "Command Cards",
            "Dice Rolling",
            "Hexagon Grid",
            "Modular Board",
            "Negotiation",
            "Tech Trees / Tech Tracks",
            "Variable Player Powers",
            "Voting",
        ],
        "boardgamedesigner": [
            "Dane Beltrami",
            "Corey Konieczka",
            "Christian T. Petersen",
        ],
        "boardgamepublisher": ["Fantasy Flight Games"],
    },
    {
        "id": 316554,
        "name": "Dune: Imperium",
        "year": 2020,
        "minplayers": 1,
        "maxplayers": 4,
        "playingtime": 120,
        "minplaytime": 60,
        "maxplaytime": 120,
        "minage": 14,
        "users_rated": 56789,
        "average": 8.3,
        "bayesaverage": 7.9,
        "owned": 78901,
        "averageweight": 3.02,
        "boardgamecategory": ["Novel-based", "Political", "Science Fiction"],
        "boardgamemechanic": [
            "Deck, Bag, and Pool Building",
            "Hand Management",
            "Worker Placement",
        ],
        "boardgamedesigner": ["Paul Dennen"],
        "boardgamepublisher": ["Dire Wolf"],
    },
    {
        "id": 187645,
        "name": "Star Wars: Rebellion",
        "year": 2016,
        "minplayers": 2,
        "maxplayers": 4,
        "playingtime": 240,
        "minplaytime": 180,
        "maxplaytime": 240,
        "minage": 14,
        "users_rated": 45678,
        "average": 8.4,
        "bayesaverage": 8.0,
        "owned": 67890,
        "averageweight": 3.70,
        "boardgamecategory": [
            "Civil War",
            "Movies / TV / Radio theme",
            "Science Fiction",
            "Wargame",
        ],
        "boardgamemechanic": [
            "Area Majority / Influence",
            "Area Movement",
            "Campaign / Battle Card Driven",
            "Dice Rolling",
            "Hand Management",
            "Variable Player Powers",
        ],
        "boardgamedesigner": ["Corey Konieczka"],
        "boardgamepublisher": ["Fantasy Flight Games"],
    },
    {
        "id": 36218,
        "name": "Dominion",
        "year": 2008,
        "minplayers": 2,
        "maxplayers": 4,
        "playingtime": 30,
        "minplaytime": 30,
        "maxplaytime": 30,
        "minage": 13,
        "users_rated": 123456,
        "average": 7.6,
        "bayesaverage": 7.5,
        "owned": 167890,
        "averageweight": 2.35,
        "boardgamecategory": ["Card Game", "Medieval"],
        "boardgamemechanic": ["Deck, Bag, and Pool Building", "Hand Management"],
        "boardgamedesigner": ["Donald X. Vaccarino"],
        "boardgamepublisher": ["Rio Grande Games"],
    },
    {
        "id": 230802,
        "name": "Azul",
        "year": 2017,
        "minplayers": 2,
        "maxplayers": 4,
        "playingtime": 45,
        "minplaytime": 30,
        "maxplaytime": 45,
        "minage": 8,
        "users_rated": 98765,
        "average": 7.8,
        "bayesaverage": 7.6,
        "owned": 145678,
        "averageweight": 1.78,
        "boardgamecategory": ["Abstract Strategy", "Puzzle"],
        "boardgamemechanic": [
            "Drafting",
            "Pattern Building",
            "Set Collection",
            "Tile Placement",
        ],
        "boardgamedesigner": ["Michael Kiesling"],
        "boardgamepublisher": ["Plan B Games"],
    },
    {
        "id": 84876,
        "name": "The Castles of Burgundy",
        "year": 2011,
        "minplayers": 2,
        "maxplayers": 4,
        "playingtime": 90,
        "minplaytime": 70,
        "maxplaytime": 90,
        "minage": 12,
        "users_rated": 89012,
        "average": 8.1,
        "bayesaverage": 7.9,
        "owned": 123456,
        "averageweight": 3.00,
        "boardgamecategory": ["Dice", "Medieval", "Territory Building"],
        "boardgamemechanic": [
            "Dice Rolling",
            "End Game Bonuses",
            "Hexagon Grid",
            "Set Collection",
            "Tile Placement",
            "Turn Order: Pass Order",
        ],
        "boardgamedesigner": ["Stefan Feld"],
        "boardgamepublisher": ["alea"],
    },
    {
        "id": 173346,
        "name": "7 Wonders Duel",
        "year": 2015,
        "minplayers": 2,
        "maxplayers": 2,
        "playingtime": 30,
        "minplaytime": 30,
        "maxplaytime": 30,
        "minage": 10,
        "users_rated": 78901,
        "average": 8.1,
        "bayesaverage": 7.8,
        "owned": 112345,
        "averageweight": 2.22,
        "boardgamecategory": ["Ancient", "Card Game", "City Building", "Civilization"],
        "boardgamemechanic": [
            "Card Drafting",
            "Open Drafting",
            "Set Collection",
            "Tech Trees / Tech Tracks",
            "Tug of War",
        ],
        "boardgamedesigner": ["Antoine Bauza", "Bruno Cathala"],
        "boardgamepublisher": ["Repos Production"],
    },
    {
        "id": 193738,
        "name": "Great Western Trail",
        "year": 2016,
        "minplayers": 2,
        "maxplayers": 4,
        "playingtime": 150,
        "minplaytime": 75,
        "maxplaytime": 150,
        "minage": 12,
        "users_rated": 56789,
        "average": 8.2,
        "bayesaverage": 7.9,
        "owned": 78901,
        "averageweight": 3.71,
        "boardgamecategory": ["American West", "Animals"],
        "boardgamemechanic": [
            "Deck, Bag, and Pool Building",
            "Hand Management",
            "Point to Point Movement",
        ],
        "boardgamedesigner": ["Alexander Pfister"],
        "boardgamepublisher": ["eggertspiele"],
    },
    {
        "id": 312484,
        "name": "Lost Ruins of Arnak",
        "year": 2020,
        "minplayers": 1,
        "maxplayers": 4,
        "playingtime": 120,
        "minplaytime": 30,
        "maxplaytime": 120,
        "minage": 12,
        "users_rated": 67890,
        "average": 8.0,
        "bayesaverage": 7.8,
        "owned": 89012,
        "averageweight": 2.90,
        "boardgamecategory": ["Adventure", "Exploration"],
        "boardgamemechanic": ["Deck, Bag, and Pool Building", "Worker Placement"],
        "boardgamedesigner": ["Mín", "Elwen"],
        "boardgamepublisher": ["Czech Games Edition"],
    },
    {
        "id": 400000,
        "name": "New Hotness 2026",
        "year": 2026,
        "minplayers": 2,
        "maxplayers": 4,
        "playingtime": 60,
        "minplaytime": 45,
        "maxplaytime": 60,
        "minage": 12,
        "users_rated": 50,  # 2026 game with 50 ratings - should be vectorized (new game exception)
        "average": 8.2,
        "bayesaverage": 7.5,
        "owned": 500,
        "averageweight": 2.80,
        "boardgamecategory": ["Card Game", "Fantasy"],
        "boardgamemechanic": ["Hand Management", "Deck, Bag, and Pool Building"],
        "boardgamedesigner": ["Jane Doe"],
        "boardgamepublisher": ["Cool Games Co"],
    },
    {
        "id": 400003,
        "name": "Recent Release 2025",
        "year": 2025,
        "minplayers": 1,
        "maxplayers": 4,
        "playingtime": 90,
        "minplaytime": 60,
        "maxplaytime": 90,
        "minage": 13,
        "users_rated": 15,  # 2025 game with 15 ratings - should be vectorized (new game exception)
        "average": 7.9,
        "bayesaverage": 7.3,
        "owned": 300,
        "averageweight": 3.10,
        "boardgamecategory": ["Strategy Game", "Economic"],
        "boardgamemechanic": ["Worker Placement", "Set Collection"],
        "boardgamedesigner": ["New Designer"],
        "boardgamepublisher": ["Rising Star Games"],
    },
    {
        "id": 400001,
        "name": "Brand New Game",
        "year": 2026,
        "minplayers": 1,
        "maxplayers": 6,
        "playingtime": 30,
        "minplaytime": 20,
        "maxplaytime": 30,
        "minage": 8,
        "users_rated": 5,  # New game with only 5 ratings - should NOT be vectorized
        "average": 7.8,
        "bayesaverage": 6.5,
        "owned": 50,
        "averageweight": 1.50,
        "boardgamecategory": ["Party Game"],
        "boardgamemechanic": ["Dice Rolling", "Push Your Luck"],
        "boardgamedesigner": ["John Smith"],
        "boardgamepublisher": ["New Publisher"],
    },
    {
        "id": 400002,
        "name": "Old Obscure Game",
        "year": 2010,
        "minplayers": 2,
        "maxplayers": 4,
        "playingtime": 90,
        "minplaytime": 60,
        "maxplaytime": 90,
        "minage": 14,
        "users_rated": 50,  # Old game with only 50 ratings - should NOT be vectorized
        "average": 7.2,
        "bayesaverage": 6.8,
        "owned": 200,
        "averageweight": 3.20,
        "boardgamecategory": ["Economic", "Medieval"],
        "boardgamemechanic": ["Worker Placement", "Trading"],
        "boardgamedesigner": ["Unknown Designer"],
        "boardgamepublisher": ["Small Publisher"],
    },
]


def create_mock_boardgame(game_data: dict) -> BoardGame:
    """Create a BoardGame object from mock data"""
    # Convert our simple dict format to BGG API format
    # BoardGame requires a 'stats' dict with specific structure
    bgg_format = {
        "id": game_data["id"],
        "name": game_data["name"],
        "yearpublished": game_data["year"],
        "minplayers": game_data["minplayers"],
        "maxplayers": game_data["maxplayers"],
        "playingtime": game_data["playingtime"],
        "minplaytime": game_data.get("minplaytime", game_data["playingtime"]),
        "maxplaytime": game_data.get("maxplaytime", game_data["playingtime"]),
        "minage": game_data.get("minage", 0),
        "boardgamecategory": game_data.get("boardgamecategory", []),
        "boardgamemechanic": game_data.get("boardgamemechanic", []),
        "boardgamedesigner": game_data.get("boardgamedesigner", []),
        "boardgameartist": game_data.get("boardgameartist", []),
        "boardgamepublisher": game_data.get("boardgamepublisher", []),
        # Required stats structure
        "stats": {
            "usersrated": game_data.get("users_rated", 0),
            "average": game_data.get("average", 0),
            "bayesaverage": game_data.get("bayesaverage", 0),
            "stddev": game_data.get("stddev", 0),
            "median": game_data.get("median", 0),
            "owned": game_data.get("owned", 0),
            "trading": game_data.get("trading", 0),
            "wanting": game_data.get("wanting", 0),
            "wishing": game_data.get("wishing", 0),
            "numcomments": game_data.get("numcomments", 0),
            "numweights": game_data.get("numweights", 0),
            "averageweight": game_data.get("averageweight", 0),
            "ranks": [
                {
                    "type": "subtype",
                    "id": 1,
                    "name": "boardgame",
                    "friendlyname": "Board Game Rank",
                    "value": game_data.get("bgg_rank", 9999),
                    "bayesaverage": game_data.get("bayesaverage", 0),
                }
            ],
        },
    }

    # Add image URLs if provided
    if "image" in game_data:
        bgg_format["image"] = game_data["image"]
    if "thumbnail" in game_data:
        bgg_format["thumbnail"] = game_data["thumbnail"]

    return BoardGame(bgg_format)


def populate_mock_games(count: int = None, min_ratings: int = None):
    """Populate game cache with mock games"""
    log.info("Starting mock game population")
    log.info(f"Cache backend: {Config.CACHE_BACKEND}")

    # Use provided min_ratings or fall back to config
    if min_ratings is None:
        min_ratings = Config.VECTOR_MIN_RATINGS

    game_cache = BoardGameFactory.create_game_cache()
    vector_sync = VectorSync(
        BoardGameFactory.create_vector_store(), min_ratings=min_ratings
    )

    games_to_add = MOCK_GAMES[:count] if count else MOCK_GAMES

    log.info(f"Adding {len(games_to_add)} mock games to cache...")
    log.info(f"Vectorizing games with {min_ratings}+ ratings only")

    cached_count = 0
    vectorized_count = 0

    for i, game_data in enumerate(games_to_add, 1):
        try:
            # Create BoardGame object
            game = create_mock_boardgame(game_data)

            # Save to cache
            game_cache.save(game)
            cached_count += 1

            # Check if vector will be generated
            should_vectorize, reason = vector_sync.should_vectorize_game(game.data())
            users_rated = game_data.get("users_rated", 0)

            # Generate vector (will be skipped if < min_ratings)
            vector_sync.sync_game(game)

            if should_vectorize:
                vectorized_count += 1
                log.info(
                    f"[{i}/{len(games_to_add)}] Added: {game.name} (id={game.id}, {users_rated} ratings) ✓ vectorized - {reason}"
                )
            else:
                log.info(
                    f"[{i}/{len(games_to_add)}] Added: {game.name} (id={game.id}, {users_rated} ratings) - skipped: {reason}"
                )

        except Exception as e:
            log.error(f"Error adding game {game_data.get('name')}: {e}")

    log.info("=" * 60)
    log.info("Mock game population complete!")
    log.info(f"Cached: {cached_count} games")
    log.info(f"Vectorized: {vectorized_count} games (with {min_ratings}+ ratings)")
    log.info(
        f"Skipped: {cached_count - vectorized_count} games (below rating threshold)"
    )
    log.info("=" * 60)
    log.info("\nYou can now test recommendations:")
    log.info("  curl -X POST http://localhost:5000/recommendations/from-games \\")
    log.info('    -H "Content-Type: application/json" \\')
    log.info('    -d \'{"game_ids": [174430, 167791], "limit": 5}\'')


def main():
    parser = argparse.ArgumentParser(
        description="Populate game cache with mock data for testing"
    )
    parser.add_argument(
        "--count",
        type=int,
        help=f"Number of games to add (max {len(MOCK_GAMES)})",
    )
    parser.add_argument(
        "--min-ratings",
        type=int,
        default=None,
        help="Minimum user ratings to vectorize a game (default: from VECTOR_MIN_RATINGS config, currently 100)",
    )

    args = parser.parse_args()

    if args.count and args.count > len(MOCK_GAMES):
        log.warning(
            f"Requested {args.count} games but only {len(MOCK_GAMES)} available"
        )
        args.count = len(MOCK_GAMES)

    try:
        Config.validate()
        populate_mock_games(count=args.count, min_ratings=args.min_ratings)
    except Exception as e:
        log.error(f"Failed to populate mock games: {e}", exc_info=True)
        sys.exit(1)


if __name__ == "__main__":
    main()
