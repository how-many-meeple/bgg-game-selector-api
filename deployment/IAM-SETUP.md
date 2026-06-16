# IAM Setup for Deployment

Minimum-privilege IAM user for deploying the BGG Game Selector API via SAM CLI.

## Quick Start

### 1. Create IAM User

1. AWS Console → **IAM** → **Users** → **Create user**
2. User name: `bgg-api-deployer`
3. Access type: **Programmatic access**

### 2. Attach Deployment Policy

1. Select **Attach policies directly** → **Create policy**
2. Switch to **JSON** tab
3. Paste contents of `deployment/iam-policy.json`
4. Name: `BGGAPIDeploymentPolicy`
5. Attach to user

### 3. Configure CLI

```bash
aws configure --profile bgg-deployer
# Enter Access Key ID and Secret Access Key
# Region: us-east-1
# Output: json
```

### 4. Verify

```bash
aws sts get-caller-identity --profile bgg-deployer
```

## What the Policy Grants

All permissions are scoped to `production-bgg-*` resources:

| Service | Actions | Scope |
|---------|---------|-------|
| CloudFormation | Stack operations | `bgg-game-selector*` stacks |
| Lambda | Function management | `production-bgg-*` functions |
| API Gateway | REST API management | All REST APIs (can't scope by name) |
| DynamoDB | Table management + TTL | `production-bgg-*` tables |
| SQS | Queue management | `production-bgg-*` queues |
| CloudWatch | Logs + alarms | `production-bgg-*` resources |
| IAM | Role creation | `production-bgg-*` and SAM roles |
| S3 | Deployment artifacts | SAM-managed buckets only |

## Changing Environment Prefix

To deploy to `staging` or `dev`, update the `production-bgg-*` patterns in `iam-policy.json` to match your environment name, then set `EnvironmentName` accordingly in your SAM deploy command.

## Cleanup

To remove the deployer user:

1. IAM → Users → `bgg-api-deployer`
2. Delete access keys
3. Detach policies
4. Delete user

Keep the user if you need to update or delete the stack later.
