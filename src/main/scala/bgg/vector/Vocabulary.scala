package bgg.vector

// Fixed vocabularies — order is part of the persisted vector format and MUST NOT change
private[bgg] val MechanicVocabulary: Vector[String] = Vector(
  "Hand Management", "Dice Rolling", "Set Collection", "Variable Player Powers",
  "Hexagon Grid", "Card Drafting", "Tile Placement", "Deck, Bag, and Pool Building",
  "Action Points", "Area Majority / Influence", "Cooperative Game", "Grid Movement",
  "Simulation", "Worker Placement", "Variable Set-up", "Modular Board",
  "Network and Route Building", "Area Movement", "Simultaneous Action Selection", "Trading",
  "Auction/Bidding", "Memory", "Push Your Luck", "Pattern Building", "Rock-Paper-Scissors",
  "Action Retrieval", "Betting and Bluffing", "Negotiation", "Pick-up and Deliver", "Race",
  "Roll / Spin and Move", "Trick-taking", "Player Elimination", "Campaign / Battle Card Driven",
  "Events", "Resource to Move", "Action Queue", "Deduction", "Drafting",
  "Turn Order: Progressive", "Turn Order: Claim Action", "Single Loser Game",
  "Point to Point Movement", "Command Cards", "Different Dice Movement", "Income",
  "Increase Value of Unchosen Resources", "Layering", "Line Drawing", "Lose a Turn",
  "Map Addition", "Map Deformation", "Map Reduction", "Matching", "Melding and Splaying",
  "Measurement Movement", "Pieces as Map", "Hidden Movement", "Move Through Deck",
  "Multiple Maps", "Multiple-Lot Auction", "Narrative Choice / Paragraph",
  "Once-Per-Game Abilities", "Open Drafting", "Order Counters", "Ownership",
  "Paper-and-Pencil", "Passed Action Token", "Pattern Recognition", "Physical Removal",
  "Predictive Bid", "Prisoner's Dilemma", "Programmed Movement", "Zone of Control",
  "Ratio / Combat Results Table", "Re-rolling and Locking", "Real-Time", "Relative Movement",
  "Rondel", "Score-and-Reset Game", "Scenario / Mission / Campaign Game",
  "Secret Unit Deployment", "Semi-Cooperative Game", "Singing", "Slide/Push",
  "Speed Matching", "Square Grid", "Square Grid - Fixed Exit", "Square Grid - Fixed Movement",
  "Stack and Balancing", "Stat Check Resolution", "Static Capture", "Stock Holding",
  "Storytelling", "Sudden Death Ending", "Tags", "Take That", "Team-Based Game",
  "Tech Trees / Tech Tracks",
)

private[bgg] val CategoryVocabulary: Vector[String] = Vector(
  "Card Game", "Fantasy", "Economic", "Fighting", "Science Fiction", "Abstract Strategy",
  "Adventure", "Wargame", "Civilization", "Medieval", "Dice", "Party Game", "Animals",
  "Exploration", "Deduction", "Puzzle", "Racing", "Horror", "Mythology", "Pirates",
  "Negotiation", "Bluffing", "Murder/Mystery", "Travel", "Trivia", "Word Game",
  "Children's Game", "Educational", "Humor", "Mature / Adult", "Memory", "Miniatures",
  "Political", "Print & Play", "Real-time", "Renaissance", "Space Exploration",
  "Spies/Secret Agents", "Territory Building", "Trains", "Transportation",
  "Video Game Theme", "Zombies", "American West", "Aviation / Flight", "City Building",
  "Environmental", "Farming", "Industry / Manufacturing", "Nautical",
)

private[bgg] val CooperativeMechanics: Set[String] =
  Set("Cooperative Game", "Semi-Cooperative Game")

val VectorDimensions: Int = MechanicVocabulary.size + CategoryVocabulary.size + 6
