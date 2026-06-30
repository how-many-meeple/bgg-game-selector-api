# BGG Game Selector API

A REST API for filtering, selecting, and recommending board games from BoardGameGeek (BGG). Caches game data to minimize API calls and runs within AWS free-tier limits.

**v3** is a full rewrite in Scala 3 targeting GraalVM native-image for Lambda deployment. The previous Python implementation is preserved on the [`python-v2`](../../tree/python-v2) branch for reference.

## v3 vs v2

| | v2 (Python) | v3 (Scala) |
|---|---|---|
| Runtime | Python 3.11, Flask | Scala 3.3 LTS, Tapir + Ox |
| Lambda packaging | Docker container (~250 MB) | GraalVM native binary (~30 MB) |
| Cold start | ~3-5 s | ~200-400 ms |
| Concurrency model | WSGI (single-threaded per request) | Structured concurrency (Ox) |
| Type safety | Runtime checks | Compile-time (opaque types, exhaustive matching) |

## Prerequisites

**BGG API Access Token**: BoardGameGeek requires authentication for all API endpoints (since December 2024).

1. Register at [BoardGameGeek API Documentation](https://boardgamegeek.com/wiki/page/BGG_XML_API2)
2. Apply for XML API 2 access and receive your token
3. Set `BGG_ACCESS_TOKEN=your_token_here` in your environment

**Build tools**: JDK 21+, sbt (installed automatically via `project/build.properties`)

## Quick Start

```bash
# Local dev with SQLite cache
export BGG_ACCESS_TOKEN=your_token_here
export CACHE_BACKEND=sqlite
make run

# Run tests
make test

# Format code
make format
```

The server starts on `http://localhost:8080`. Use `CACHE_BACKEND=memory` for ephemeral cache (useful for tests).

## Architecture

```
              POST /prefetch
Client ──────────────────────────────────────────────────────┐
  │                                                          v
  │ GET /collection, /geeklist, /recommendations   ┌─────────────────┐
  v                                                │ Step Functions   │
┌─────────────────────────┐                        │ (STANDARD)       │
│  API Lambda             │                        └────────┬────────┘
│  (Tapir + Netty Sync)   │                                 │
│  GraalVM native binary  │         ┌───────────────────────┼──────────────┐
└───────────┬─────────────┘         │                       │              │
            │                  CollectionFetch         PlaysFetchPage   GameFetch
            v                  (ID resolution)        (page loop)      (batched)
┌─────────────────────────┐         │                       │              │
│  BGG XML API            │ <───────┴───────────────────────┴──────────────┘
│  (v1 geeklists, v2 all) │
└───────────┬─────────────┘
            │
            v
┌───────────────────────────────────────────┐
│  DynamoDB (prod) / SQLite (local)         │
│  ├─ Game Cache (7 day TTL)                │
│  ├─ Plays Cache (chunked, incremental)    │
│  ├─ Vector Store (game embeddings)        │
│  └─ Prefetch Status (per-status TTLs)     │
└───────────────────────────────────────────┘
```

### Caching Strategy

- **Game Cache**: Individual game details cached for 7 days. Fetches from BGG on miss.
- **Plays Cache**: Chunked per-page storage with incremental updates. Only fetches new plays since the last cached play ID.
- **Vector Store**: Game feature vectors for the recommendation engine. Updated on cache writes when the game has enough ratings.
- **Prefetch Status**: Tracks async BGG fetch jobs with per-status TTLs. Avoids the API Gateway 29s timeout on slow BGG responses.

### Recommendation Engine

v3 adds a vector-based recommendation system:
1. Each game is vectorized across mechanics, categories, and numeric features
2. User's taste vector is built from their collection
3. Cosine similarity scores candidate games against the taste vector
4. Results are filtered using the same header-based filters as collection endpoints

## API Endpoints

### GET /health

Returns service status and cache backend.

### GET /collection/:username

Games from a BGG user's collection, filtered via headers.

### GET /geeklist/:id

Games from a BGG GeekList, filtered via headers.

### GET /game/:id

Fetch a single game by BGG ID. Returns from cache if available, otherwise fetches from BGG.

**Request:**
```
GET /game/174430
```

**Response (200):**
```json
{
  "id": 174430,
  "name": "Gloomhaven",
  "yearpublished": 2017,
  "minplayers": 1,
  "maxplayers": 4,
  "minplaytime": 60,
  "maxplaytime": 120,
  "playingtime": 120,
  "image": "https://cf.geekdo-images.com/sZYp_3BTDGjh2unaZfZmuA__original/img/...",
  "thumbnail": "https://cf.geekdo-images.com/sZYp_3BTDGjh2unaZfZmuA__thumb/img/...",
  "expansion": false,
  "mechanics": ["Action Queue", "Campaign / Battle Card Driven", "Hand Management"],
  "categories": ["Adventure", "Exploration", "Fantasy", "Fighting"],
  "stats": {
    "average": 8.2536,
    "averageweight": 3.8637,
    "usersrated": 58000
  },
  "suggested_players": {
    "2": {"results": {"best": 500, "recommended": 800, "not_recommended": 50}},
    "3": {"results": {"best": 900, "recommended": 400, "not_recommended": 20}}
  }
}
```

**Error (404):**
```json
{"error": "Game 99999 not found"}
```

### GET /hot

Trending games from BGG's hotness list.

### GET /plays/:username

Play history for a BGG user, grouped by game. Includes metadata indicating whether the plays fetch is still in progress.

### GET /search/:query

Search BGG for games by name (minimum 3 characters).

### POST /prefetch

Starts an async prefetch via Step Functions. Returns immediately so the frontend doesn't block.

```json
{"source_type": "collection", "source_id": "username"}
```

### GET /prefetch/status/:sourceType/:sourceId

Poll prefetch job status: `pending` | `processing` | `completed` | `not_found` | `failed`

### POST /recommendations/from-games

Get game recommendations based on a set of game IDs.

```json
{"gameIds": [174430, 167791], "limit": 10, "excludeIds": []}
```

### GET /recommendations/schema

Returns the vector schema (dimensions, mechanic/category vocabulary).

### GET /cors-proxy/:b64url

Proxies an image URL (base64url-encoded) with immutable cache headers. Used by the frontend for BGG thumbnails.

## Filter Headers

All collection/geeklist endpoints accept filtering via HTTP headers:

| Header | Type | Description |
|--------|------|-------------|
| `Bgg-Filter-Player-Count` | int | Number of players |
| `Bgg-Filter-Min-Duration` | int | Minimum duration (minutes) |
| `Bgg-Filter-Max-Duration` | int | Maximum duration (minutes) |
| `Bgg-Filter-Complexity` | float | Target complexity (1-5); matches within +/-1 |
| `Bgg-Filter-Min-Rating` | float | Minimum BGG rating |
| `Bgg-Filter-Mechanic` | string | Required mechanic(s), comma-separated |
| `Bgg-Filter-Using-Recommended-Players` | bool | Use community player count recommendations (default: true) |
| `Bgg-Include-Expansions` | bool | Include expansions (default: false) |
| `Bgg-Field-Whitelist` | string | Comma-separated fields to return |

## Configuration

All config is via environment variables (with defaults in `application.conf`):

| Variable | Default | Description |
|----------|---------|-------------|
| `BGG_ACCESS_TOKEN` | *(required)* | BGG API access token |
| `CACHE_BACKEND` | `dynamodb` | `dynamodb`, `sqlite`, or `memory` |
| `BGG_TIMEOUT` | `60` | BGG API timeout (seconds) |
| `BGG_RETRIES` | `6` | BGG API retry attempts |
| `BGG_RETRY_DELAY` | `10` | Delay between retries (seconds) |
| `REQUEST_CACHE_DURATION` | `86400` | Request cache TTL (seconds) |
| `GAME_CACHE_DURATION` | `604800` | Game cache TTL (seconds) |
| `VECTOR_MIN_RATINGS` | `100` | Min ratings for vectorization |
| `AWS_REGION` | `us-east-1` | AWS region |
| `PREFETCH_STATE_MACHINE_ARN` | *(required in prod)* | Step Functions state machine ARN for prefetch |
| `SERVER_HOST` | `0.0.0.0` | Server bind host |
| `SERVER_PORT` | `8080` | Server bind port |

## Development

```bash
make compile    # Compile
make test       # Run all tests
make format     # scalafmt
make lint       # Check formatting
make assembly   # Build fat jar
make run        # Run locally (fat jar, JVM mode)
```

### Tech Stack

- **Scala 3.3 LTS** on JDK 21
- **Tapir** — type-safe endpoint definitions with Netty sync server
- **Ox** — structured concurrency (direct style, no IO monad)
- **sttp client4** — synchronous HTTP client for BGG API
- **Circe** — JSON codec derivation
- **scala-xml** — BGG XML API parsing
- **AWS SDK v2** — DynamoDB, Step Functions (url-connection-client for GraalVM compatibility)
- **SQLite** — local dev/test cache backend
- **ScalaTest + ScalaMock** — testing

### Running Tests

```bash
# All tests
make test

# Specific suite
sbt "testOnly bgg.routes.ApiEndpointsSpec"

# With coverage
sbt coverage test coverageReport
```

## Deployment

### Building the Native Image

```bash
make native
```

This runs the Docker-based GraalVM build (`deployment/Dockerfile.native`) and produces `deployment/bgg-api-native.zip` — a single `bootstrap` binary for Lambda custom runtime (provided.al2023).

### Deploying to AWS

```bash
sam deploy \
  --template-file deployment/serverless-template.yaml \
  --stack-name bgg-game-selector-api \
  --capabilities CAPABILITY_IAM \
  --region us-east-1 \
  --parameter-overrides \
    EnvironmentName=production \
    BggAccessToken="your-token-here" \
  --resolve-s3 \
  --no-confirm-changeset
```

The SAM template provisions:
- API Gateway (rate-limited, CloudWatch logging)
- API Lambda (native binary, 512 MB, 30s timeout)
- Step Functions state machine (STANDARD, orchestrates prefetch)
- 5 worker Lambdas (CollectionFetch, PlaysFetchPage, GameFetch, BatchPreparer, StatusUpdater)
- DynamoDB tables (PAY_PER_REQUEST, TTL-enabled)
- EventBridge rule (weekly hot list warm)
- CloudWatch alarms for free-tier monitoring

### Cost

Designed for AWS free-tier. At typical hobby usage (~1,000 requests/day) the monthly cost is $0. DynamoDB on-demand pricing + automatic TTL means zero cost when idle.

## Previous Versions

The Python v2 codebase is on the [`python-v2`](../../tree/python-v2) branch. It is no longer maintained but serves as reference for the API contract and caching strategy that v3 preserves.
