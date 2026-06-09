# Deployment Guide

## Overview

This API can be deployed using either:
1. **Serverless (Recommended)** - AWS Lambda + API Gateway (FREE TIER optimized)
2. **Container** - ECS Fargate with ALB (~$30/month minimum)

**Recommendation**: Use serverless deployment for cost optimization and free-tier compliance.

## Serverless Deployment (FREE TIER)

### Prerequisites

- AWS Account
- AWS CLI configured (`aws configure`)
- AWS SAM CLI installed ([installation guide](https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/install-sam-cli.html))
- Python 3.11+
- IAM permissions (see [Required IAM Permissions](#required-iam-permissions) below)

### Cost Breakdown (Monthly)

| Service | Free Tier | Typical Usage | Cost |
|---------|-----------|---------------|------|
| Lambda | 1M requests, 400K GB-seconds | 500K requests | **$0.00** |
| API Gateway | 1M calls | 500K calls | **$0.00** |
| DynamoDB | 25GB storage, 25 RCU/WCU | 10GB, 20 RCU/WCU | **$0.00** |
| CloudWatch | 5GB logs | 1GB logs | **$0.00** |
| **TOTAL** | | | **$0.00** |

> **Note**: Exceeding free tier limits will incur charges. Set up billing alarms!

### Security Features

✅ **Rate Limiting** - API Gateway throttling (100 req/min per IP)  
✅ **HTTPS Only** - API Gateway enforces TLS 1.2+  
✅ **DDoS Protection** - AWS Shield Standard (automatic)  
✅ **IAM Permissions** - Least privilege access to DynamoDB  
✅ **CloudWatch Alarms** - Alerts at 80% of free tier usage  
✅ **No Public IP** - Lambda runs in AWS managed VPC  

### Required IAM Permissions

To deploy this stack, you need an IAM user or role with specific permissions. Two options:

#### Option 1: Quick Setup (AWS Console)

1. Go to IAM → Users → Create User (or use existing user)
2. Click "Add permissions" → "Create inline policy"
3. Switch to JSON tab
4. Copy and paste the contents from `deployment/iam-policy.json`
5. Name it `BGGAPIDeploymentPolicy`

The policy grants least-privilege permissions for:
- CloudFormation stack management
- Lambda function creation/updates
- API Gateway configuration
- DynamoDB table management (with TTL)
- CloudWatch Logs and Alarms
- S3 (for SAM deployment artifacts)
- IAM role creation (for Lambda execution roles)

**Scope:** All resources are limited to `production-bgg-*` prefix. No account-wide permissions.

#### Option 2: Use AWS Managed Policy (Less Secure)

If you have `AdministratorAccess` or `PowerUserAccess`, you already have sufficient permissions. Not recommended for production deployments.

#### What Each Permission Does

| Service | Actions | Why Needed |
|---------|---------|------------|
| CloudFormation | Stack operations | Deploy and manage the infrastructure |
| Lambda | Function management | Create and update the API function |
| API Gateway | API management | Configure HTTP endpoints |
| DynamoDB | Table management | Create cache table with TTL |
| CloudWatch | Logs and alarms | Monitor costs and performance |
| IAM | Role creation | Lambda needs execution role for DynamoDB access |
| S3 | Deployment bucket | SAM packages code here before deployment |

**Security Note:** The policy uses resource restrictions (`production-bgg-*`) to limit scope. Change the prefix in `iam-policy.json` if using a different environment name.

### Step-by-Step Deployment

#### 1. Install Dependencies

```bash
# Install AWS SAM CLI (if not already installed)
pip install aws-sam-cli

# Verify installation
sam --version
```

#### 2. Build the Application

```bash
cd bgg-game-selector-api

# Install dependencies into build directory
pip install -r requirements.txt -t ./build
cp -r boardgame build/
cp app.py config.py lambda_handler.py build/
```

#### 3. Deploy with SAM

```bash
# Navigate to deployment directory
cd deployment

# Deploy using SAM (guided mode for first deployment)
sam deploy \
  --template-file serverless-template.yaml \
  --stack-name bgg-game-selector-api \
  --capabilities CAPABILITY_IAM \
  --parameter-overrides \
    EnvironmentName=production \
    RateLimitPerMinute=100 \
    BurstLimit=200 \
  --guided
```

**Guided Prompts**:
- Stack name: `bgg-game-selector-api`
- Region: `us-east-1` (or your preferred region)
- Confirm changes: `Y`
- Allow SAM CLI IAM role creation: `Y`
- Save arguments to config: `Y`

#### 4. Get API Endpoint

```bash
# Get the API endpoint URL
aws cloudformation describe-stacks \
  --stack-name bgg-game-selector-api \
  --query 'Stacks[0].Outputs[?OutputKey==`ApiEndpoint`].OutputValue' \
  --output text
```

Example output:
```
https://abc123xyz.execute-api.us-east-1.amazonaws.com/production
```

#### 5. Test the Deployment

```bash
# Health check
curl https://YOUR-API-ID.execute-api.us-east-1.amazonaws.com/production/health

# Test collection endpoint
curl -H "Bgg-Filter-Player-Count: 4" \
  https://YOUR-API-ID.execute-api.us-east-1.amazonaws.com/production/collection/your-username
```

### Monitoring & Alarms

The deployment automatically creates CloudWatch alarms:

1. **High Request Alarm** - Triggers at 800K requests/day (80% of free tier)
2. **High DynamoDB Reads** - Triggers at 50K reads/hour (unusual activity)

View alarms:
```bash
aws cloudwatch describe-alarms \
  --alarm-name-prefix production-bgg
```

### Updating the Deployment

```bash
# Make code changes, then rebuild
pip install -r requirements.txt -t ./build
cp -r boardgame build/

# Redeploy
cd deployment
sam deploy
```

### Cleanup (Delete Stack)

```bash
# Delete all resources
aws cloudformation delete-stack --stack-name bgg-game-selector-api

# Verify deletion
aws cloudformation describe-stacks --stack-name bgg-game-selector-api
```

> **Warning**: This deletes the DynamoDB table and all cached data!

---

## Container Deployment (ECS Fargate)

**Cost**: ~$30/month minimum (NOT free tier)

This option is included for completeness but is NOT recommended for cost optimization.

### Prerequisites

- AWS Account
- AWS CLI configured
- Docker installed
- ECR repository created

### Step 1: Build and Push Docker Image

```bash
# Create ECR repository
aws ecr create-repository --repository-name bgg-game-selector-api

# Get ECR login token
aws ecr get-login-password --region us-east-1 | \
  docker login --username AWS --password-stdin <account-id>.dkr.ecr.us-east-1.amazonaws.com

# Build image
docker build -t bgg-game-selector-api .

# Tag image
docker tag bgg-game-selector-api:latest \
  <account-id>.dkr.ecr.us-east-1.amazonaws.com/bgg-game-selector-api:latest

# Push to ECR
docker push <account-id>.dkr.ecr.us-east-1.amazonaws.com/bgg-game-selector-api:latest
```

### Step 2: Deploy CloudFormation Stack

```bash
aws cloudformation create-stack \
  --stack-name bgg-game-selector-ecs \
  --template-body file://cloudformation-template.yaml \
  --parameters \
    ParameterKey=VpcId,ParameterValue=vpc-xxxxx \
    ParameterKey=SubnetIds,ParameterValue="subnet-xxxxx,subnet-yyyyy" \
    ParameterKey=DockerImage,ParameterValue="<account-id>.dkr.ecr.us-east-1.amazonaws.com/bgg-game-selector-api:latest" \
  --capabilities CAPABILITY_IAM
```

### Cost Breakdown (Monthly)

| Service | Cost |
|---------|------|
| Application Load Balancer | ~$16.20 |
| Fargate vCPU (0.25 vCPU) | ~$8.90 |
| Fargate Memory (0.5 GB) | ~$1.00 |
| DynamoDB (on-demand) | ~$1.00 |
| **TOTAL** | **~$27.10** |

---

## Security Best Practices

### 1. Enable AWS WAF (Optional, $5/month)

For additional DDoS protection:

```bash
# Uncomment WebACL section in serverless-template.yaml
# Then redeploy
sam deploy
```

### 2. Set Up Billing Alarms

```bash
aws cloudwatch put-metric-alarm \
  --alarm-name billing-alert \
  --alarm-description "Alert when estimated charges exceed $10" \
  --metric-name EstimatedCharges \
  --namespace AWS/Billing \
  --statistic Maximum \
  --period 21600 \
  --evaluation-periods 1 \
  --threshold 10 \
  --comparison-operator GreaterThanThreshold
```

### 3. Enable API Key (Optional)

Uncomment API key requirement in `serverless-template.yaml`:

```yaml
Auth:
  ApiKeyRequired: true
```

Create and distribute API keys:

```bash
aws apigateway create-api-key --name "BGG-API-Key" --enabled
```

### 4. Rotate Credentials

Lambda uses IAM roles (no long-lived credentials). No rotation needed!

### 5. Monitor DynamoDB Costs

```bash
# View DynamoDB metrics
aws cloudwatch get-metric-statistics \
  --namespace AWS/DynamoDB \
  --metric-name ConsumedReadCapacityUnits \
  --dimensions Name=TableName,Value=production-bgg-game-cache \
  --start-time 2024-01-01T00:00:00Z \
  --end-time 2024-01-02T00:00:00Z \
  --period 3600 \
  --statistics Sum
```

---

## Troubleshooting

### Lambda Function Timing Out

Increase timeout in `serverless-template.yaml`:

```yaml
Globals:
  Function:
    Timeout: 60  # Increase from 30 to 60 seconds
```

### DynamoDB Throttling

Check consumed capacity:

```bash
aws dynamodb describe-table \
  --table-name production-bgg-game-cache \
  --query 'Table.BillingModeSummary'
```

### High Lambda Costs

Check invocation count:

```bash
aws cloudwatch get-metric-statistics \
  --namespace AWS/Lambda \
  --metric-name Invocations \
  --dimensions Name=FunctionName,Value=production-bgg-api \
  --start-time $(date -u -d '1 day ago' +%Y-%m-%dT%H:%M:%S) \
  --end-time $(date -u +%Y-%m-%dT%H:%M:%S) \
  --period 3600 \
  --statistics Sum
```

### Cannot Import 'boardgamegeek'

Package dependencies:

```bash
pip install -r requirements.txt -t ./build
```

---

## Performance Optimization

### 1. Increase Lambda Memory

More memory = faster CPU:

```yaml
Globals:
  Function:
    MemorySize: 1024  # Increase from 512 to 1024 MB
```

### 2. Enable Lambda SnapStart (for Java)

Not applicable for Python.

### 3. Use DynamoDB Global Tables

For multi-region deployment (additional cost).

### 4. Implement CloudFront CDN

Cache API responses at edge locations (free tier: 1TB data transfer out).

---

## Cost Monitoring Commands

```bash
# Get current month's estimated charges
aws ce get-cost-and-usage \
  --time-period Start=$(date +%Y-%m-01),End=$(date +%Y-%m-%d) \
  --granularity MONTHLY \
  --metrics BlendedCost \
  --group-by Type=SERVICE

# DynamoDB consumed capacity
aws cloudwatch get-metric-statistics \
  --namespace AWS/DynamoDB \
  --metric-name ConsumedReadCapacityUnits \
  --dimensions Name=TableName,Value=production-bgg-game-cache \
  --start-time $(date -u -d '1 hour ago' +%Y-%m-%dT%H:%M:%S) \
  --end-time $(date -u +%Y-%m-%dT%H:%M:%S) \
  --period 3600 \
  --statistics Sum

# Lambda invocation count (today)
aws cloudwatch get-metric-statistics \
  --namespace AWS/Lambda \
  --metric-name Invocations \
  --dimensions Name=FunctionName,Value=production-bgg-api \
  --start-time $(date -u -d '1 day ago' +%Y-%m-%dT%H:%M:%S) \
  --end-time $(date -u +%Y-%m-%dT%H:%M:%S) \
  --period 86400 \
  --statistics Sum
```

---

## FAQ

**Q: How much does this cost?**  
A: $0/month if you stay within free tier limits (1M Lambda requests, 25GB DynamoDB, 1M API Gateway calls).

**Q: What happens if I exceed free tier?**  
A: You'll be charged: Lambda $0.20 per 1M requests, API Gateway $3.50 per 1M requests, DynamoDB $0.25 per 1M reads.

**Q: Is this secure?**  
A: Yes. HTTPS only, rate limiting, IAM permissions, no public IPs, AWS Shield Standard DDoS protection.

**Q: Can I use a custom domain?**  
A: Yes! Use Route53 + ACM certificate with API Gateway custom domain mapping.

**Q: How do I scale this?**  
A: Lambda auto-scales to 1000 concurrent executions. No configuration needed.

**Q: What about cold starts?**  
A: First request after idle takes ~2-3 seconds. Provisioned concurrency available but costs extra.
