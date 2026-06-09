# IAM Setup for Deployment

This guide walks through creating an IAM user with the minimum permissions needed to deploy the BGG Game Selector API.

## Quick Start (AWS Console)

### Step 1: Create IAM User

1. Log into AWS Console
2. Go to **IAM** → **Users** → **Create user**
3. User name: `bgg-api-deployer`
4. Access type: **Programmatic access** (check this box)
5. Click **Next**

### Step 2: Attach Deployment Policy

1. Select **Attach policies directly**
2. Click **Create policy**
3. Switch to the **JSON** tab
4. Copy the entire contents from `deployment/iam-policy.json`
5. Paste into the policy editor
6. Click **Next: Tags** (skip tags)
7. Click **Next: Review**
8. Policy name: `BGGAPIDeploymentPolicy`
9. Click **Create policy**
10. Go back to user creation, refresh policies, search for `BGGAPIDeploymentPolicy`
11. Check the box next to it
12. Click **Next: Review** → **Create user**

### Step 3: Save Credentials

**IMPORTANT:** Save the Access Key ID and Secret Access Key shown on the next screen. You cannot retrieve the secret key later.

```bash
# Configure AWS CLI with these credentials
aws configure --profile bgg-deployer
# Enter Access Key ID when prompted
# Enter Secret Access Key when prompted
# Default region: us-east-1
# Default output format: json
```

### Step 4: Test Permissions

```bash
# Test that credentials work
aws sts get-caller-identity --profile bgg-deployer

# Should output:
# {
#   "UserId": "AIDAXXXXXXXXXX",
#   "Account": "123456789012",
#   "Arn": "arn:aws:iam::123456789012:user/bgg-api-deployer"
# }
```

## What Permissions Are Granted?

The policy in `iam-policy.json` grants **least-privilege** access for deployment:

### CloudFormation
- Create, update, delete stacks
- Only for stacks named `bgg-game-selector*`

### Lambda
- Create and manage functions
- Only functions named `production-bgg-api*`

### API Gateway
- Create and configure REST APIs
- All REST APIs (can't scope by name in API Gateway)

### DynamoDB
- Create table, enable TTL, tag resources
- Only table named `production-bgg-game-cache`

### CloudWatch
- Create log groups and alarms
- Only for `/aws/lambda/production-bgg-api*` and `production-bgg-*` alarms

### IAM
- Create execution roles for Lambda
- Only roles named `production-bgg-*`
- Cannot modify other IAM resources

### S3
- Read/write deployment artifacts
- Only SAM CLI managed buckets

## Security Notes

✅ **Resource Restrictions**: All permissions are scoped to specific resource names with `production-bgg-*` prefix  
✅ **No Account-Wide Access**: Cannot modify other services or resources  
✅ **Read-Only Where Possible**: IAM policy reading is read-only, no creation of arbitrary policies  
✅ **Temporary Credentials**: Use this user only for deployment, then store credentials in AWS Secrets Manager or environment variables  

## Changing the Environment Prefix

If deploying to a different environment (e.g., `staging`, `dev`), update these lines in `iam-policy.json`:

```json
"Resource": "arn:aws:cloudformation:*:*:stack/bgg-game-selector*/*"
// Change to:
"Resource": "arn:aws:cloudformation:*:*:stack/staging-bgg-game-selector*/*"

"Resource": "arn:aws:lambda:*:*:function:production-bgg-api*"
// Change to:
"Resource": "arn:aws:lambda:*:*:function:staging-bgg-api*"

// ...and so on for all resources
```

Then update `serverless-template.yaml` parameter:

```yaml
EnvironmentName:
  Type: String
  Default: staging  # changed from production
```

## Alternative: Using AWS Organizations

For multiple environments across AWS accounts, create this policy in the organization's management account, then assume a role with these permissions in each member account.

## Troubleshooting

### "User is not authorized to perform: cloudformation:CreateStack"

The IAM policy hasn't attached correctly. Check:
1. Policy JSON has no syntax errors
2. User has the policy attached (IAM → Users → [username] → Permissions tab)
3. You're using the correct AWS CLI profile

### "Access Denied" creating Lambda function

The Lambda function name doesn't match the pattern in the policy. Check:
1. Function name starts with `production-bgg-api`
2. The `EnvironmentName` parameter in your deployment matches

### "Access Denied" on S3 bucket

SAM CLI creates a managed bucket. First deployment may fail if the bucket doesn't exist yet. Run:

```bash
sam deploy --guided --profile bgg-deployer
```

SAM will create the bucket automatically.

## Cleanup

To remove the IAM user after deployment:

1. Go to **IAM** → **Users** → `bgg-api-deployer`
2. **Security credentials** tab → Delete access keys
3. **Permissions** tab → Detach/delete policies
4. Delete user

**Note:** Keep the user if you need to update or delete the stack later.

## Next Steps

Once IAM is set up, proceed to the main deployment guide: [DEPLOYMENT.md](./DEPLOYMENT.md)
