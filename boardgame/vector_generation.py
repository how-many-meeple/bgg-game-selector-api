"""
Deterministic vector generation for board games.

Converts game metadata into fixed-length numeric vectors for similarity comparison.

Vector Schema (total dimensions computed based on unique mechanics/categories):
- [0:N_MECHANICS]: Multi-hot encoding for mechanics (1 if present, 0 otherwise)
- [N_MECHANICS:N_MECHANICS+N_CATEGORIES]: Multi-hot encoding for categories
- [..+0]: Normalized complexity/weight (0-1 scale, 0-5 BGG scale)
- [..+1]: Normalized playtime in minutes (0-1 scale, 0-240 min)
- [..+2]: Normalized min_players (0-1 scale, 1-10)
- [..+3]: Normalized max_players (0-1 scale, 1-10)
- [..+4]: Normalized rating (0-1 scale, 0-10 BGG scale)
- [..+5]: Cooperative flag (1 if cooperative, 0 otherwise)
"""

import logging
import math
from typing import Any

log = logging.getLogger(__name__)

# Top 100 most common BGG mechanics
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

# Top 50 most common BGG categories
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
    """Generates fixed-length vector representations of board games"""

    @staticmethod
    def generate(game_data: dict[str, Any]) -> list[float]:
        """
        Generate a fixed-length vector representation of a game.

        Args:
            game_data: BoardGame.data() dictionary or equivalent game metadata

        Returns:
            Fixed-length vector of floats
        """
        vector = [0.0] * VECTOR_DIMENSIONS
        idx = 0

        # Multi-hot encoding for mechanics
        game_mechanics = set(game_data.get("mechanics", []))
        for mechanic in MECHANIC_VOCABULARY:
            vector[idx] = 1.0 if mechanic in game_mechanics else 0.0
            idx += 1

        # Multi-hot encoding for categories
        game_categories = set(game_data.get("categories", []))
        for category in CATEGORY_VOCABULARY:
            vector[idx] = 1.0 if category in game_categories else 0.0
            idx += 1

        # Normalized complexity/weight (0-5 BGG scale -> 0-1)
        weight = game_data.get("rating_average_weight", 0) or 0
        vector[idx] = GameVectorGenerator._normalize(weight, 0, 5)
        idx += 1

        # Normalized playtime (capped at 240 minutes)
        playtime = (
            game_data.get("playing_time")
            or game_data.get("max_playing_time")
            or game_data.get("maxplaytime")
            or 0
        )
        vector[idx] = GameVectorGenerator._normalize(playtime, 0, 240)
        idx += 1

        # Normalized min_players (1-10 scale)
        min_players = game_data.get("min_players") or game_data.get("minplayers") or 1
        vector[idx] = GameVectorGenerator._normalize(min_players, 1, 10)
        idx += 1

        # Normalized max_players (1-10 scale)
        max_players = game_data.get("max_players") or game_data.get("maxplayers") or 1
        vector[idx] = GameVectorGenerator._normalize(max_players, 1, 10)
        idx += 1

        # Normalized rating (0-10 BGG scale -> 0-1)
        rating = game_data.get("rating_average", 0) or 0
        vector[idx] = GameVectorGenerator._normalize(rating, 0, 10)
        idx += 1

        # Cooperative flag
        is_cooperative = bool(game_mechanics & COOPERATIVE_MECHANICS)
        vector[idx] = 1.0 if is_cooperative else 0.0

        return vector

    @staticmethod
    def _normalize(value: float, min_val: float, max_val: float) -> float:
        """Normalize a value to 0-1 range, clamping to bounds"""
        if max_val == min_val:
            return 0.0
        value = max(min_val, min(max_val, value))
        return (value - min_val) / (max_val - min_val)

    @staticmethod
    def get_schema() -> dict[str, Any]:
        """Return the vector schema documentation"""
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
    """Builds aggregate taste vectors from collections of games"""

    @staticmethod
    def build(games: list[dict[str, Any]]) -> list[float]:
        """
        Build a taste vector from a list of games by aggregating their features.

        Strategy:
        - For multi-hot features (mechanics/categories): sum frequencies, normalize by max
        - For numeric features: average values
        - For binary features: majority vote (>50% = 1)

        Args:
            games: List of game data dictionaries

        Returns:
            Aggregated taste vector matching game vector dimensionality
        """
        if not games:
            log.warning("Cannot build taste vector from empty game list")
            return [0.0] * VECTOR_DIMENSIONS

        # Generate vectors for all games
        game_vectors = [GameVectorGenerator.generate(game) for game in games]
        n_games = len(game_vectors)

        # Initialize aggregated vector
        taste_vector = [0.0] * VECTOR_DIMENSIONS

        # Sum all vectors
        for game_vec in game_vectors:
            for i in range(VECTOR_DIMENSIONS):
                taste_vector[i] += game_vec[i]

        # Compute indices for different feature types
        n_mechanics = len(MECHANIC_VOCABULARY)
        n_categories = len(CATEGORY_VOCABULARY)
        multihot_end = n_mechanics + n_categories
        numeric_start = multihot_end
        numeric_end = numeric_start + 5
        binary_start = numeric_end

        # Normalize multi-hot features (mechanics + categories)
        max_freq = (
            max(taste_vector[:multihot_end])
            if max(taste_vector[:multihot_end]) > 0
            else 1
        )
        for i in range(multihot_end):
            taste_vector[i] = taste_vector[i] / max_freq

        # Average numeric features
        for i in range(numeric_start, numeric_end):
            taste_vector[i] = taste_vector[i] / n_games

        # Binary features: majority vote
        for i in range(binary_start, VECTOR_DIMENSIONS):
            taste_vector[i] = 1.0 if taste_vector[i] / n_games > 0.5 else 0.0

        return taste_vector


class VectorSimilarity:
    """Computes similarity metrics between vectors"""

    @staticmethod
    def cosine_similarity(vec_a: list[float], vec_b: list[float]) -> float:
        """
        Compute cosine similarity between two vectors.

        Returns value in range [-1, 1] where:
        - 1.0 = identical vectors
        - 0.0 = orthogonal (no similarity)
        - -1.0 = opposite vectors

        Args:
            vec_a: First vector
            vec_b: Second vector

        Returns:
            Cosine similarity score
        """
        if len(vec_a) != len(vec_b):
            log.error(f"Vector dimension mismatch: {len(vec_a)} vs {len(vec_b)}")
            return 0.0

        dot_product = sum(a * b for a, b in zip(vec_a, vec_b))

        magnitude_a = math.sqrt(sum(a * a for a in vec_a))
        magnitude_b = math.sqrt(sum(b * b for b in vec_b))

        if magnitude_a == 0.0 or magnitude_b == 0.0:
            return 0.0

        return dot_product / (magnitude_a * magnitude_b)
