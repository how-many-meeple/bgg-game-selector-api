"""Deterministic vector generation for board games."""

import logging
import math
from typing import Any

log = logging.getLogger(__name__)

MECHANIC_VOCABULARY = [
    "Hand Management",
    "Dice Rolling",
    "Set Collection",
    "Variable Player Powers",
    "Hexagon Grid",
    "Card Drafting",
    "Tile Placement",
    "Deck, Bag, and Pool Building",
    "Action Points",
    "Area Majority / Influence",
    "Cooperative Game",
    "Grid Movement",
    "Simulation",
    "Worker Placement",
    "Variable Set-up",
    "Modular Board",
    "Network and Route Building",
    "Area Movement",
    "Simultaneous Action Selection",
    "Trading",
    "Auction/Bidding",
    "Memory",
    "Push Your Luck",
    "Pattern Building",
    "Rock-Paper-Scissors",
    "Action Retrieval",
    "Betting and Bluffing",
    "Negotiation",
    "Pick-up and Deliver",
    "Race",
    "Roll / Spin and Move",
    "Trick-taking",
    "Player Elimination",
    "Campaign / Battle Card Driven",
    "Events",
    "Resource to Move",
    "Action Queue",
    "Deduction",
    "Drafting",
    "Turn Order: Progressive",
    "Turn Order: Claim Action",
    "Single Loser Game",
    "Point to Point Movement",
    "Command Cards",
    "Different Dice Movement",
    "Income",
    "Increase Value of Unchosen Resources",
    "Layering",
    "Line Drawing",
    "Lose a Turn",
    "Map Addition",
    "Map Deformation",
    "Map Reduction",
    "Matching",
    "Melding and Splaying",
    "Measurement Movement",
    "Pieces as Map",
    "Hidden Movement",
    "Move Through Deck",
    "Multiple Maps",
    "Multiple-Lot Auction",
    "Narrative Choice / Paragraph",
    "Once-Per-Game Abilities",
    "Open Drafting",
    "Order Counters",
    "Ownership",
    "Paper-and-Pencil",
    "Passed Action Token",
    "Pattern Recognition",
    "Physical Removal",
    "Predictive Bid",
    "Prisoner's Dilemma",
    "Programmed Movement",
    "Zone of Control",
    "Ratio / Combat Results Table",
    "Re-rolling and Locking",
    "Real-Time",
    "Relative Movement",
    "Rondel",
    "Score-and-Reset Game",
    "Scenario / Mission / Campaign Game",
    "Secret Unit Deployment",
    "Semi-Cooperative Game",
    "Singing",
    "Slide/Push",
    "Speed Matching",
    "Square Grid",
    "Square Grid - Fixed Exit",
    "Square Grid - Fixed Movement",
    "Stack and Balancing",
    "Stat Check Resolution",
    "Static Capture",
    "Stock Holding",
    "Storytelling",
    "Sudden Death Ending",
    "Tags",
    "Take That",
    "Team-Based Game",
    "Tech Trees / Tech Tracks",
]

CATEGORY_VOCABULARY = [
    "Card Game",
    "Fantasy",
    "Economic",
    "Fighting",
    "Science Fiction",
    "Abstract Strategy",
    "Adventure",
    "Wargame",
    "Civilization",
    "Medieval",
    "Dice",
    "Party Game",
    "Animals",
    "Exploration",
    "Deduction",
    "Puzzle",
    "Racing",
    "Horror",
    "Mythology",
    "Pirates",
    "Negotiation",
    "Bluffing",
    "Murder/Mystery",
    "Travel",
    "Trivia",
    "Word Game",
    "Children's Game",
    "Educational",
    "Humor",
    "Mature / Adult",
    "Memory",
    "Miniatures",
    "Political",
    "Print & Play",
    "Real-time",
    "Renaissance",
    "Space Exploration",
    "Spies/Secret Agents",
    "Territory Building",
    "Trains",
    "Transportation",
    "Video Game Theme",
    "Zombies",
    "American West",
    "Aviation / Flight",
    "City Building",
    "Environmental",
    "Farming",
    "Industry / Manufacturing",
    "Nautical",
]

COOPERATIVE_MECHANICS = {"Cooperative Game", "Semi-Cooperative Game"}

VECTOR_DIMENSIONS = len(MECHANIC_VOCABULARY) + len(CATEGORY_VOCABULARY) + 6


class GameVectorGenerator:
    @staticmethod
    def generate(game_data: dict[str, Any]) -> list[float]:
        vector = [0.0] * VECTOR_DIMENSIONS
        idx = 0

        game_mechanics = set(game_data.get("mechanics", []))
        for mechanic in MECHANIC_VOCABULARY:
            vector[idx] = 1.0 if mechanic in game_mechanics else 0.0
            idx += 1

        game_categories = set(game_data.get("categories", []))
        for category in CATEGORY_VOCABULARY:
            vector[idx] = 1.0 if category in game_categories else 0.0
            idx += 1

        weight = game_data.get("rating_average_weight", 0) or 0
        vector[idx] = GameVectorGenerator._normalize(weight, 0, 5)
        idx += 1

        playtime = (
            game_data.get("playing_time")
            or game_data.get("max_playing_time")
            or game_data.get("maxplaytime")
            or 0
        )
        vector[idx] = GameVectorGenerator._normalize(playtime, 0, 240)
        idx += 1

        min_players = game_data.get("min_players") or game_data.get("minplayers") or 1
        vector[idx] = GameVectorGenerator._normalize(min_players, 1, 10)
        idx += 1

        max_players = game_data.get("max_players") or game_data.get("maxplayers") or 1
        vector[idx] = GameVectorGenerator._normalize(max_players, 1, 10)
        idx += 1

        rating = game_data.get("rating_average", 0) or 0
        vector[idx] = GameVectorGenerator._normalize(rating, 0, 10)
        idx += 1

        is_cooperative = bool(game_mechanics & COOPERATIVE_MECHANICS)
        vector[idx] = 1.0 if is_cooperative else 0.0

        return VectorSimilarity.l2_normalise(vector)

    @staticmethod
    def _normalize(value: float, min_val: float, max_val: float) -> float:
        if max_val == min_val:
            return 0.0
        value = max(min_val, min(max_val, value))
        return (value - min_val) / (max_val - min_val)

    @staticmethod
    def get_schema() -> dict[str, Any]:
        return {
            "total_dimensions": VECTOR_DIMENSIONS,
            "mechanics": {
                "start_index": 0,
                "end_index": len(MECHANIC_VOCABULARY),
                "count": len(MECHANIC_VOCABULARY),
                "vocabulary": MECHANIC_VOCABULARY,
            },
            "categories": {
                "start_index": len(MECHANIC_VOCABULARY),
                "end_index": len(MECHANIC_VOCABULARY) + len(CATEGORY_VOCABULARY),
                "count": len(CATEGORY_VOCABULARY),
                "vocabulary": CATEGORY_VOCABULARY,
            },
            "numeric_features": {
                "start_index": len(MECHANIC_VOCABULARY) + len(CATEGORY_VOCABULARY),
                "features": [
                    {"name": "weight", "scale": "0-5 normalized to 0-1"},
                    {"name": "playtime", "scale": "0-240 minutes normalized to 0-1"},
                    {"name": "min_players", "scale": "1-10 normalized to 0-1"},
                    {"name": "max_players", "scale": "1-10 normalized to 0-1"},
                    {"name": "rating", "scale": "0-10 normalized to 0-1"},
                ],
            },
            "binary_features": {
                "start_index": len(MECHANIC_VOCABULARY) + len(CATEGORY_VOCABULARY) + 5,
                "features": [{"name": "is_cooperative", "values": "0 or 1"}],
            },
        }


class TasteVectorBuilder:

    @staticmethod
    def build(games: list[dict[str, Any]]) -> list[float]:
        if not games:
            log.warning("Cannot build taste vector from empty game list")
            return [0.0] * VECTOR_DIMENSIONS

        game_vectors = [GameVectorGenerator.generate(game) for game in games]
        n_games = len(game_vectors)

        taste_vector = [0.0] * VECTOR_DIMENSIONS
        for game_vec in game_vectors:
            for i in range(VECTOR_DIMENSIONS):
                taste_vector[i] += game_vec[i]

        n_mechanics = len(MECHANIC_VOCABULARY)
        n_categories = len(CATEGORY_VOCABULARY)
        multihot_end = n_mechanics + n_categories
        numeric_start = multihot_end
        numeric_end = numeric_start + 5
        binary_start = numeric_end

        max_freq = (
            max(taste_vector[:multihot_end])
            if max(taste_vector[:multihot_end]) > 0
            else 1
        )
        for i in range(multihot_end):
            taste_vector[i] = taste_vector[i] / max_freq

        for i in range(numeric_start, numeric_end):
            taste_vector[i] = taste_vector[i] / n_games

        for i in range(binary_start, VECTOR_DIMENSIONS):
            taste_vector[i] = 1.0 if taste_vector[i] / n_games > 0.5 else 0.0

        return VectorSimilarity.l2_normalise(taste_vector)


class VectorSimilarity:
    @staticmethod
    def l2_normalise(vector: list[float]) -> list[float]:
        magnitude = math.sqrt(sum(x * x for x in vector))
        if magnitude == 0.0:
            return list(vector)
        return [x / magnitude for x in vector]

    @staticmethod
    def cosine_similarity(vec_a: list[float], vec_b: list[float]) -> float:
        if len(vec_a) != len(vec_b):
            log.error(f"Vector dimension mismatch: {len(vec_a)} vs {len(vec_b)}")
            return 0.0

        if not any(vec_a) or not any(vec_b):
            return 0.0

        return sum(a * b for a, b in zip(vec_a, vec_b))
