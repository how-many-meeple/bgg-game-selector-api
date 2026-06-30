# Deployment Guide

## Overview

The v3 API deploys as a GraalVM native binary on AWS Lambda (custom runtime `provided.al2023`). This gives ~200-400ms cold starts and runs entirely within AWS free-tier limits.

**Prerequisites:**
- AWS CLI + SAM CLI configured
- Docker (for native image build)
- IAM permissions — see [IAM-SETUP.md](./IAM-SETUP.md)
- BGG API access token

## Build

```bash
# From project root — builds the fat jar then native-image via Docker
make native
```

This produces `deployment/bgg-api-native.zip` containing a single `bootstrap` binary (~30 MB).

Under the hood:
1. `sbt assembly` builds the fat jar
2. Docker runs GraalVM `native-image` against the jar (using `Dockerfile.native`)
3. The resulting binary is packaged as a Lambda custom runtime zip

## Deploy

```bash
cd deployment

sam deploy \
  --template-file serverless-template.yaml \
  --stack-name bgg-game-selector-api \
  --capabilities CAPABILITY_IAM \
  --region us-east-1 \
  --profile bgg-deployer \
  --parameter-overrides \
    EnvironmentName=production \
    RateLimitPerMinute=100 \
    BurstLimit=200 \
    AllowedOrigins="https://*.howmanymeeple.com" \
    BggAccessToken="your-bgg-api-token" \
  --resolve-s3 \
  --no-confirm-changeset
```

### First Deployment

Use `--guided` for interactive prompts:

```bash
sam deploy --guided --profile bgg-deployer
```

### Get API Endpoint

```bash
aws cloudformation describe-stacks \
  --stack-name bgg-game-selector-api \
  --query 'Stacks[0].Outputs[?OutputKey==`ApiEndpoint`].OutputValue' \
  --output text
```

## What Gets Created

The SAM template provisions:

| Resource | Purpose |
|----------|---------|
| API Lambda | Native binary, 512 MB, 30s timeout |
| Step Functions state machine | Orchestrates prefetch (collection → games → plays) |
| CollectionFetch Lambda | Resolves game IDs from BGG (300s timeout) |
| PlaysFetchPage Lambda | Fetches one page of plays incrementally (120s timeout) |
| GameFetch Lambda | Fetches game details in parallel batches of 20 (300s timeout) |
| BatchPreparer Lambda | Filters cached IDs, splits into 300-game chunks (30s timeout) |
| StatusUpdater Lambda | Writes prefetch status transitions (10s timeout) |
| API Gateway | REST API with rate limiting and CloudWatch logging |
| DynamoDB tables (x5) | Request cache, game cache, plays cache, vectors, prefetch status |
| EventBridge rule | Weekly hot list warm via Step Functions |
| CloudWatch alarms | Free-tier usage warnings |

## Cost

| Service | Free Tier | Typical Usage | Monthly Cost |
|---------|-----------|---------------|--------------|
| Lambda | 1M requests | ~500K | $0.00 |
| API Gateway | 1M calls | ~500K | $0.00 |
| DynamoDB | 25GB, 25 RCU/WCU | ~5GB | $0.00 |
| Step Functions | 4,000 transitions | ~1,000 | $0.00 |
| **Total** | | | **$0.00** |

## Updating

```bash
make native
cd deployment
sam deploy
```

## Custom Domain

To map a custom domain:

1. Create ACM certificate in us-east-1
2. Create API Gateway custom domain mapping
3. Point Route53 (or DNS) at the API Gateway domain

## Teardown

```bash
aws cloudformation delete-stack --stack-name bgg-game-selector-api
```

This deletes all resources including DynamoDB tables — cached data will be lost.

## Troubleshooting

### Lambda Timeout

The API function has a 30s timeout (API Gateway hard limit). Slow BGG fetches should use `POST /prefetch` — the Step Functions state machine orchestrates individual Lambdas (each with 30s-300s timeouts) and has no overall time limit.

### Cold Start > 1s

Ensure you're deploying the native binary (not the JVM fat jar). Check the CodeUri points to `bgg-api-native.zip`. JVM cold starts are 5-10s; native should be 200-400ms.

### Build Fails on native-image

GraalVM native-image requires reflection configuration for some libraries. If adding new dependencies, you may need to update `META-INF/native-image` configs. Run with `-agentlib:native-image-agent` locally to generate them.

### "Access Denied" on Deploy

Check IAM policy is attached. See [IAM-SETUP.md](./IAM-SETUP.md).
