# BGG Game Selector API

A cost-optimized REST API for filtering and selecting board games from BoardGameGeek (BGG). Caches game data to minimize API calls and reduce AWS costs. Runs within AWS free-tier limits.

## Prerequisites

**BGG API Access Token Required**: As of December 2024, BoardGameGeek requires authentication for all API endpoints. You must register for API access and obtain an access token before using this application.

**How to obtain access**:
1. Register at [BoardGameGeek API Documentation](https://boardgamegeek.com/wiki/page/BGG_XML_API2)
2. Apply for XML API 2 access
3. Receive your access token
4. Configure in `.env`: `BGG_ACCESS_TOKEN=your_token_here`

## Features

- **Smart Caching**: Two-tier caching strategy minimizes BGG API calls
  - Request-level cache (24 hours) for collections and geeklists
  - Game-level cache (7 days) with DynamoDB (production) or memory (local dev)
- **Flexible Filtering**: Filter games by players, duration, complexity, mechanics, rating, and expansions
- **Field Selection**: Reduce response payload by selecting only needed fields
- **Cost Optimized**: Built for AWS free-tier with DynamoDB on-demand pricing and automatic TTL
- **Modern Stack**: Python 3.11+, Flask, secure BGG API library

## Architecture

```
┌─────────────┐         POST /prefetch
│   Client    │ ──────────────────────────────────────────────┐
└──────┬──────┘                                               │
       │ GET /collection, /geeklist                           v
       v                                             ┌─────────────────┐
┌─────────────────┐   check prefetch status          │   Flask API     │
│   Flask API     │ ──────────────────────────────>  │  (app.py)       │
│   (app.py)      │   not_found → 404                │                 │
└────────┬────────┘   failed    → 503                └────────┬────────┘
         │            otherwise → proceed                      │ enqueue
         │                                                     v
         ├──> Request Cache (Memory/DynamoDB, 24h TTL) ┌─────────────┐
         │                                              │  SQS Queue  │
         v                                              └──────┬──────┘
┌──────────────────┐                                          │
│ BGG API Client   │  <───────────────────────────────────────┘
│  (bgg-api lib)   │       PrefetchWorkerFunction
└────────┬─────────┘       (15-min timeout, writes
         │                  pending/processing/completed/
         v                  not_found/failed to DynamoDB)
┌──────────────────┐
│   Game Cache     │
│ DynamoDB/Memory  │
│   (7 day TTL)    │
└──────────────────┘
```

### Caching Strategy

1. **Request Cache** (Memory/DynamoDB): Caches raw BGG API responses for 24 hours
   - Collections (`/collection/<user>`)
   - GeekLists (`/geeklist/<id>`)
   - Searches (`/search/<query>`)

2. **Game Cache** (DynamoDB or Memory): Caches individual game details for 7 days
   - Automatically fetches missing games from BGG
   - Uses DynamoDB TTL for zero-cost expiration in production
   - Falls back to in-memory cache for local development (lost on restart)

3. **Prefetch Status** (DynamoDB/SQLite): Tracks async BGG fetch jobs with per-status TTLs
   - Avoids the API Gateway 29s hard timeout on slow BGG responses
   - Frontend calls `POST /prefetch` when adding a new source; the worker Lambda fetches in the background
   - `GET /collection` and `GET /geeklist` check this status and return 404/503 with the original error reason if the prefetch failed

This approach means:
- Collection requests return instantly from cache after prefetch completes
- Slow or failing BGG responses never time out the API Gateway
- BGG API is touched minimally (typically once per game per week)

## Installation

### Requirements

- Python 3.11+
- pip
- **BGG API access token** (required for all BGG data)
- AWS account (optional, for DynamoDB in production)

### Local Development Setup

```bash
# Clone the repository
git clone <repository-url>
cd bgg-game-selector-api

# Create virtual environment
python -m venv venv
source venv/bin/activate  # On Windows: venv\Scripts\activate

# Install dependencies
pip install -r requirements.txt
pip install -r requirements-dev.txt  # For testing

# Configure environment
cp .env.example .env
# REQUIRED: Add your BGG API token to .env
# BGG_ACCESS_TOKEN=your_token_here
# Set CACHE_BACKEND=sqlite for local development (persistent cache)
# Set CACHE_BACKEND=memory for ephemeral cache (lost on restart)
# Set CACHE_BACKEND=dynamodb for production/AWS

# Run the application
python app.py
```

**Note**: The application will start without a BGG token, but all BGG API calls will fail with 401 errors.

### Production Deployment (AWS)

See [Deployment](#deployment) section below.

## Configuration

Configuration is managed through environment variables. Copy `.env.example` to `.env` and adjust values:

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `BGG_ACCESS_TOKEN` | *(required)* | **BGG API access token** - obtain from BGG registration |
| `CACHE_BACKEND` | `sqlite` | Cache backend: `dynamodb` (AWS), `sqlite` (local persistent), or `memory` (local temp) |
| `BGG_TIMEOUT` | `60` | BGG API request timeout (seconds) |
| `BGG_RETRY_DELAY` | `10` | Delay between BGG API retries (seconds) |
| `BGG_RETRIES` | `6` | Number of BGG API retry attempts |
| `REQUEST_CACHE_DURATION` | `86400` | Request cache TTL (seconds, 24 hours) for collections/searches |
| `GAME_CACHE_DURATION` | `604800` | Game cache TTL (seconds, 7 days) for individual games |
| `DYNAMODB_REQUEST_TABLE` | `bgg-request-cache` | DynamoDB table for requests (when `CACHE_BACKEND=dynamodb`) |
| `DYNAMODB_GAME_TABLE` | `bgg-game-cache` | DynamoDB table for game data (when `CACHE_BACKEND=dynamodb`) |
| `DYNAMODB_PREFETCH_TABLE` | `bgg-prefetch-status` | DynamoDB table for prefetch job status (when `CACHE_BACKEND=dynamodb`) |
| `PREFETCH_SQS_URL` | *(required in production)* | SQS queue URL for prefetch jobs |
| `SQLITE_REQUEST_CACHE_PATH` | `bgg_request_cache.sqlite` | SQLite file for requests (when `CACHE_BACKEND=sqlite`) |
| `SQLITE_GAME_CACHE_PATH` | `bgg_game_cache.sqlite` | SQLite file for game data (when `CACHE_BACKEND=sqlite`) |
| `SQLITE_PREFETCH_STATUS_PATH` | `bgg_prefetch_status.sqlite` | SQLite file for prefetch job status (when `CACHE_BACKEND=sqlite`) |
| `FLASK_ENV` | `production` | Flask environment |
| `ALLOWED_ORIGINS` | `*` | CORS allowed origins (comma-separated). Use `*` for local dev, specific domains for production |

### DynamoDB Setup

The DynamoDB table is automatically created with proper configuration:

```python
from boardgame.game_cache import DynamoDBGameCache

# Create table with TTL enabled
DynamoDBGameCache.create_table(
    table_name='bgg-game-cache',
    region='us-east-1'
)
```

Or use the AWS CLI:

```bash
aws dynamodb create-table \
    --table-name bgg-game-cache \
    --attribute-definitions AttributeName=id,AttributeType=S \
    --key-schema AttributeName=id,KeyType=HASH \
    --billing-mode PAY_PER_REQUEST \
    --region us-east-1

# Enable TTL
aws dynamodb update-time-to-live \
    --table-name bgg-game-cache \
    --time-to-live-specification "Enabled=true, AttributeName=ttl" \
    --region us-east-1
```

## API Endpoints

### Get User Collection

```http
GET /collection/<username>
```

Retrieves and filters games from a BoardGameGeek user's collection.

**Path Parameters:**
- `username` (string): BGG username

**Response:**
```json
[
  {
    "id": 174430,
    "name": "Gloomhaven",
    "yearpublished": 2017,
    "minplayers": 1,
    "maxplayers": 4,
    "playingtime": 120,
    "rating_average": 8.8,
    ...
  }
]
```

### Get GeekList

```http
GET /geeklist/<geek_list>
```

Retrieves and filters games from a BGG GeekList.

**Path Parameters:**
- `geek_list` (string): BGG GeekList ID (or comma-separated IDs)

### Queue a Prefetch

```http
POST /prefetch
```

Queues an async BGG fetch for a collection or geeklist. Returns immediately with 202 so the frontend can proceed without waiting for BGG. Call this when a user adds a new source.

**Request body:**
```json
{
  "source_type": "collection",
  "source_id": "username"
}
```

`source_type` must be `"collection"` or `"geeklist"`.

**Responses:**
- `202` — job queued, status is `pending`
- `200` — already queued or recently completed (returns current status)
- `400` — missing or invalid fields

### Check Prefetch Status

```http
GET /prefetch/status/<source_type>/<source_id>
```

Returns the current prefetch job status. Useful for the frontend to poll while a job is in flight.

**Statuses:**

| Status | Meaning |
|--------|---------|
| `pending` | Queued, not yet picked up by worker |
| `processing` | Worker is actively fetching from BGG |
| `completed` | Fetch succeeded; collection/geeklist endpoint will serve data |
| `not_found` | BGG returned no data for this source |
| `failed` | Unexpected error during fetch; retryable |

**Responses:**
- `200` — status record found
- `404` — no prefetch has been run for this source

### Search Games

```http
GET /search/<string>
```

Search for games by name on BoardGameGeek.

**Path Parameters:**
- `string` (string): Search query (minimum 3 characters)

**Response:**
```json
[
  {
    "id": 174430,
    "name": "Gloomhaven",
    ...
  }
]
```

## Request Headers (Filtering)

All collection and geeklist endpoints support filtering via HTTP headers:

### Filter Headers

| Header | Type | Description | Example |
|--------|------|-------------|---------|
| `Bgg-Filter-Player-Count` | Integer | Number of players | `4` |
| `Bgg-Filter-Min-Duration` | Integer | Minimum game duration (minutes) | `30` |
| `Bgg-Filter-Max-Duration` | Integer | Maximum game duration (minutes) | `120` |
| `Bgg-Filter-Complexity` | Float | Target complexity weight (1-5); includes games within ±1 of the value | `3.0` |
| `Bgg-Filter-Min-Rating` | Float | Minimum BGG rating | `7.5` |
| `Bgg-Filter-Mechanic` | String | Required mechanic(s) | `Cooperative Play, Worker Placement` |
| `Bgg-Filter-Using-Recommended-Players` | Boolean | Use community player count recommendations | `true` |
| `Bgg-Include-Expansions` | Boolean | Include expansion games | `false` |
| `Bgg-Field-Whitelist` | String | Comma-separated field names to include | `id,name,rating_average` |

### Example Requests

#### Find 4-player games under 90 minutes

```bash
curl -H "Bgg-Filter-Player-Count: 4" \
     -H "Bgg-Filter-Max-Duration: 90" \
     http://localhost:8080/collection/username
```

#### Find cooperative games with rating > 7.5

```bash
curl -H "Bgg-Filter-Mechanic: Cooperative Play" \
     -H "Bgg-Filter-Min-Rating: 7.5" \
     http://localhost:8080/collection/username
```

#### Return only specific fields

```bash
curl -H "Bgg-Field-Whitelist: id,name,rating_average,playingtime" \
     http://localhost:8080/collection/username
```

## Cost Optimization

Cost optimization for AWS free-tier:

### DynamoDB Free Tier

- 25 GB of storage (enough for ~1 million cached games)
- 25 read capacity units (RCU) and 25 write capacity units (WCU)
- Pay-per-request pricing eliminates charges when idle
- Automatic TTL expiration (zero cost)

### Estimated Costs

**Scenario**: API serving 1,000 game lookups per day

- **Cache hit rate**: ~80% (after warm-up)
- **DynamoDB reads**: ~200/day (cache misses)
- **DynamoDB writes**: ~200/day (new games)
- **Free tier**: 2.5M reads, 1M writes per month
- **Monthly cost**: $0 (within free tier)

**Even at 10,000 requests/day**, costs remain minimal:
- 2,000 cache misses/day = 60,000/month (still within free tier)

### Cost Reduction Tips

1. **Increase cache duration** for stable game data
2. **Use request-level caching** for frequently accessed collections
3. **Enable field whitelisting** at the client to reduce response sizes
4. **Monitor DynamoDB metrics** in CloudWatch

## Development

### Running Tests

```bash
# Run all tests
pytest

# Run with coverage
pytest --cov=boardgame --cov-report=html

# Run specific test file
pytest tests/boardgame/test_game_cache.py

# Run RightBICEP cache tests
pytest tests/boardgame/test_game_cache.py tests/boardgame/test_cache_dynamodb.py -v
```

### Code Quality

```bash
# Format code
black .

# Lint
python3 -m flake8 .
```

### Testing with Different Cache Backends

```bash
# Test with memory cache (default for local dev)
CACHE_BACKEND=memory pytest

# Test with DynamoDB (requires moto for mocking)
CACHE_BACKEND=dynamodb pytest tests/boardgame/test_request_cache.py
```

## Deployment

**Prerequisites:** Set up IAM permissions first. See [deployment/IAM-SETUP.md](deployment/IAM-SETUP.md) for step-by-step instructions.

Full deployment guide: [deployment/DEPLOYMENT.md](deployment/DEPLOYMENT.md)

### Docker

```bash
# Build image
docker build -t bgg-game-selector-api .

# Run container
docker run -p 8080:8080 \
  -e CACHE_BACKEND=dynamodb \
  -e DYNAMODB_REQUEST_TABLE=bgg-request-cache \
  -e DYNAMODB_GAME_TABLE=bgg-game-cache \
  -e AWS_REGION=us-east-1 \
  -e BGG_ACCESS_TOKEN=your_token_here \
  bgg-game-selector-api
```

### AWS Elastic Container Service (ECS)

1. Push Docker image to ECR
2. Create ECS task definition with environment variables
3. Configure IAM role with DynamoDB permissions
4. Deploy as ECS service behind Application Load Balancer

### AWS Lambda + API Gateway

For serverless deployment with even lower costs:

```bash
# Build with Docker
python3 -m samcli build --use-container --template-file deployment/serverless-template.yaml --profile howmanymeeple

# Deploy with BGG token
python3 -m samcli deploy \
  --template-file .aws-sam/build/template.yaml \
  --stack-name bgg-game-selector-api \
  --capabilities CAPABILITY_IAM \
  --region us-east-1 \
  --profile howmanymeeple \
  --parameter-overrides \
    EnvironmentName=production \
    RateLimitPerMinute=100 \
    BurstLimit=200 \
    AllowedOrigins="https://*.howmanymeeple.com" \
    BggAccessToken="your-bgg-api-token-here" \
  --resolve-s3 \
  --no-confirm-changeset
```

**Important:** Replace `your-bgg-api-token-here` with your actual BGG API token.

See `deployment/` directory for full infrastructure-as-code templates.

## BGG XML API 2 Reference

This API uses the [BoardGameGeek XML API2](https://boardgamegeek.com/wiki/page/BGG_XML_API2):

- **Base URL**: `https://www.boardgamegeek.com/xmlapi2/`
- **Rate Limit**: ~60 requests per minute
- **Response Format**: XML (parsed by bgg-api library)
- **Key Endpoints**: `/thing`, `/collection`, `/search`

### BGG API Behavior

- **202 Response**: Data still processing, retry required
- **429 Response**: Rate limit exceeded
- **Retry Logic**: Built into `bgg-api` library with configurable delays

## Troubleshooting

### BGG API Returns 401 Unauthorized

**Cause**: Missing or invalid `BGG_ACCESS_TOKEN`

**Solution**:
1. Check `.env` file has `BGG_ACCESS_TOKEN=your_token_here`
2. Verify token is not expired
3. Confirm BGG API registration is approved
4. Contact BGG support if token doesn't work

### "No module named 'boardgamegeek'"

The library name changed. Install the new version:

```bash
pip install bgg-api
```

### App Starts But No Game Data

**Cause**: Empty or invalid `BGG_ACCESS_TOKEN`

**Expected Behavior**: 
- App starts successfully
- Health check endpoint works
- BGG API calls fail gracefully
- Logs show authentication errors

**Solution**: Add valid BGG access token to `.env`

### DynamoDB Access Denied

IAM role/user needs these permissions:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "dynamodb:GetItem",
        "dynamodb:PutItem",
        "dynamodb:Query",
        "dynamodb:Scan"
      ],
      "Resource": "arn:aws:dynamodb:*:*:table/bgg-game-cache"
    }
  ]
}
```

### BGG API Timeouts

Increase retry settings in `.env`:

```bash
BGG_TIMEOUT=120
BGG_RETRIES=10
```

### High Cache Miss Rate

Check DynamoDB CloudWatch metrics:
- Increase `GAME_CACHE_DURATION` if game data is stable
- Verify TTL is properly configured
- Monitor ConsumedReadCapacityUnits

## Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Follow the existing code style (Black formatting)
4. Add tests for new functionality (RightBICEP principles)
5. Commit changes using conventional commits (`git commit -m 'feat: amazing-feature'`)
6. Push to branch (`git push origin feature/amazing-feature`)
7. Open a Pull Request

### Commit Message Format

Use [Conventional Commits](https://www.conventionalcommits.org/):

- `feat:` - New feature
- `fix:` - Bug fix
- `docs:` - Documentation changes
- `test:` - Adding or updating tests
- `refactor:` - Code refactoring
- `chore:` - Maintenance tasks

## License

[Add your license here]

## Acknowledgments

- [BoardGameGeek](https://boardgamegeek.com/) for providing the XML API
- [bgg-api](https://github.com/SukiCZ/boardgamegeek) library maintainers
- Original [boardgamegeek2](https://github.com/lcosmin/boardgamegeek) library
