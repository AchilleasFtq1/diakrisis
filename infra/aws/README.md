# Diakrisis on AWS

> **The seam is real, not a slide.** Diakrisis already talks to its data store through the **AWS SDK
> v2 DynamoDB enhanced client** (`@DynamoDbBean`, `DynamoDbEnhancedClient`). Locally it points at
> DynamoDB Local; in production it points at AWS DynamoDB. **Same code, one endpoint difference.**
> This folder is the proof: real Infrastructure-as-Code you can `cloudformation deploy`.

## Money-free path (this runs at **$0**)

You do **not** need to pay to put Diakrisis on AWS:

1. **AWS Free Tier — DynamoDB is *always free*** up to 25 GB storage + 25 WCU + 25 RCU. The demo's
   data volume is a few MB and a handful of requests, so the whole persistence layer is **$0,
   indefinitely** — not a 12-month trial.
2. **Hackathon credits.** iFX Hack 2026 is *Powered by AWS*. AWS hackathons almost always provide an
   **AWS credit pool** to participants — **ask the organizers / the AWS booth** before the event. With
   credits, the *whole* stack (services on Fargate/Lambda, OpenSearch, Bedrock) is free too.
3. **On-demand billing** (`PAY_PER_REQUEST`) — no provisioned capacity to pay for while idle.

## Deploy the persistence layer (≈2 minutes)

```bash
# real AWS DynamoDB tables — exactly the ones the code reads/writes
aws cloudformation deploy \
  --template-file infra/aws/dynamodb.yaml \
  --stack-name diakrisis-data \
  --region eu-central-1

# point the running services at AWS instead of local DynamoDB (no code change):
unset DIAKRISIS_DYNAMO_ENDPOINT          # SDK now uses the real AWS endpoint
export AWS_REGION=eu-central-1           # + standard AWS creds (env / profile / role)
# re-seed from Berka into AWS:
java -jar etl/target/etl.jar --berka-dir ../data/raw/berka --demo   # --ddb-endpoint omitted → AWS
```

That's the demo running against **real AWS DynamoDB**, including native **TTL** doing the 72h
kill-chain decay (`ttlEpochSec` on `AccountPosture`, `Observations`, `CounterpartyReputation`,
`RefreshTokens`) — the store ages itself, no cron, no Lambda.

## Full production architecture (SDD §16)

Each local component maps 1:1 to a managed AWS service. Nothing is re-architected — the seams already
exist in the code (interfaces: `ExemplarIndex`, `AiCoJudge`, `GeoResolver`, the `DynamoDbTable` beans).

| Local (demo) | AWS (production) | Why |
|---|---|---|
| DynamoDB Local | **DynamoDB** (on-demand, PITR) | Key-value at any scale; **TTL = the 72h posture decay, 1:1** — a tidy answer |
| `api-gateway` (Spring Cloud Gateway) | **API Gateway (HTTP API)** + WAF | Edge auth, throttling, the JWT authorizer; 1M calls/mo free (12 mo) |
| `decision-service` jar | **ECS Fargate** *or* **Lambda SnapStart** (Java) | Fargate for steady traffic; SnapStart trims Java cold start for spiky load |
| `iam` / `ops` services | ECS Fargate tasks (same task def pattern) | Stateless, horizontal; per-bank stages + KMS |
| Qdrant (M2 k-NN) | **OpenSearch Serverless (k-NN)** *or* Aurora **pgvector** | Managed vector search behind the same `ExemplarIndex` interface |
| Ollama + Gemma (co-judge) | **Bedrock** (or a GPU EC2 / SageMaker endpoint) | Managed LLM behind the same `AiCoJudge` interface; advisory, time-boxed |
| ETL (Berka/IEEE-CIS) | **Kinesis → S3 (immutable audit)** + a scheduled Fargate task | Streaming ingest; S3 as the tamper-evident decision/audit log |
| Model retraining | **SageMaker** (or scheduled Fargate) on the feedback loop | The `outcomes` table (confirmed-save / false-positive) is the training signal |
| GeoIP | **MaxMind GeoLite2** on the container | Behind the `GeoResolver` interface — zero hot-path network dependency |
| JWT secret | **Secrets Manager** + **KMS** | No secret in env/image in production |

### The one-line answer for an AWS Solutions Architect

> "It's a stateless Spring Boot reactor on **Fargate** behind **API Gateway**, with **DynamoDB** as the
> single-digit-ms key store — and the 72-hour kill-chain posture is just **DynamoDB TTL**, so the store
> ages itself. Vectors go to **OpenSearch k-NN**, the LLM co-judge to **Bedrock**, the feedback loop to
> **SageMaker**. Every one of those sits behind an interface that already exists in the code, so the
> swap is config, not a rewrite — which is why I can deploy the data layer right now from this
> CloudFormation."

## Why this is credible without a live AWS deployment

- The code **already** uses the AWS SDK v2 DynamoDB enhanced client — not a homegrown abstraction.
- The 72h decay is **already** modelled as a `ttlEpochSec` attribute → it *is* a DynamoDB TTL field.
- Every external dependency (vector store, LLM, geo) is **already** behind a Java interface with a
  swappable implementation (`KdTreeExemplarIndex` ↔ `QdrantExemplarIndex`, etc.).
- This template is **real and deployable** — not pseudocode on a slide.
