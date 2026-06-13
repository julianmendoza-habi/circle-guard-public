# CircleGuard AWS Infrastructure — Architecture

Two views: the **AWS resource topology** Terraform provisions, and the **logical mapping**
of the 8 microservices to their data stores. Diagrams are Mermaid so they render directly in
GitHub / VS Code; export to PNG for the presentation if needed.

## 1. AWS resource topology

```mermaid
flowchart TB
    subgraph AWS["AWS Account / Region (us-east-1)"]
        IGW["Internet Gateway"]
        ECR["ECR<br/>8 image repos<br/>(scan_on_push)"]

        subgraph VPC["VPC 10.0.0.0/16"]
            subgraph PUB["Public subnets (per AZ)"]
                NAT["NAT Gateway(s)<br/>1 in dev/stage, 1-per-AZ in prod"]
                ELB["Public LoadBalancer(s)<br/>(k8s Services)"]
            end

            subgraph PRIV["Private subnets (per AZ)"]
                subgraph EKS["EKS cluster (managed node group)"]
                    APPS["8 Spring Boot pods<br/>(auth, identity, form, promotion,<br/>notification, dashboard, file, gateway)"]
                    NEO["Neo4j (in-cluster, EBS PVC)"]
                    LDAP["OpenLDAP (in-cluster)"]
                    KAFKAIC["Kafka/ZK (in-cluster<br/>when enable_msk=false)"]
                end
                RDS[("RDS PostgreSQL 16<br/>5 logical DBs")]
                REDIS[("ElastiCache Redis 7")]
                MSK[("MSK Kafka<br/>prod only, enable_msk=true")]
            end
        end
    end

    Internet(("Internet")) --> IGW --> ELB --> APPS
    APPS -->|JDBC 5432| RDS
    APPS -->|6379| REDIS
    APPS -->|9092| KAFKAIC
    APPS -.->|"prod 9092/9094"| MSK
    APPS -->|bolt 7687| NEO
    APPS -->|389| LDAP
    PRIV -->|egress| NAT --> IGW
    EKS -->|pull images| ECR

    classDef managed fill:#e8f0fe,stroke:#4285f4;
    class RDS,REDIS,MSK,ECR managed;
```

Security groups: RDS (5432), ElastiCache (6379) and MSK (9092/9094) ingress is restricted to
the **EKS cluster security group** only — no public access. State lives in an S3 bucket
(versioned, encrypted, private) with a DynamoDB lock table.

## 2. Logical service → store mapping

```mermaid
flowchart LR
    subgraph svc["Microservices"]
        AUTH[auth]
        IDENT[identity]
        FORM[form]
        PROMO[promotion]
        NOTIF[notification]
        DASH[dashboard]
        FILE[file]
        GATE[gateway]
    end

    AUTH --> RDS[(RDS Postgres)]
    IDENT --> RDS
    FORM --> RDS
    PROMO --> RDS
    DASH --> RDS

    PROMO --> NEO[(Neo4j in-cluster)]
    GATE --> REDIS[(ElastiCache Redis)]
    PROMO --> REDIS

    FORM -->|publish| KAFKA{{"Kafka: MSK or in-cluster"}}
    PROMO --> KAFKA
    KAFKA -->|consume| NOTIF

    AUTH --> LDAP[(OpenLDAP in-cluster)]
    FILE -.->|"S3/MinIO planned"| S3[(future)]

    classDef managed fill:#e8f0fe,stroke:#4285f4;
    class RDS,REDIS,KAFKA managed;
```

## Environment sizing matrix

| Variable | dev | stage | prod (`master`) |
|---|---|---|---|
| AZs | 1 | 2 | 3 |
| `single_nat_gateway` | true | true | false |
| `node_instance_types` | t3.medium | t3.medium | t3.large |
| node desired/min/max | 2 / 1 / 3 | 2 / 2 / 4 | 3 / 3 / 6 |
| `capacity_type` | SPOT | SPOT | ON_DEMAND |
| `rds_instance_class` | db.t4g.micro | db.t4g.micro | db.t4g.small |
| `rds_multi_az` | false | false | true |
| `redis_node_type` | cache.t4g.micro | cache.t4g.micro | cache.t4g.small |
| `redis_num_nodes` / failover | 1 / off | 1 / off | 2 / on |
| `enable_msk` | false | false | true |

## State backend

```mermaid
flowchart LR
    BOOT["bootstrap/<br/>(local state)"] -->|creates| S3[("S3 bucket<br/>circleguard-tfstate-(acct)<br/>versioned + encrypted")]
    BOOT -->|creates| DDB[("DynamoDB<br/>circleguard-tflock")]
    DEV["environments/dev"] -->|key dev/| S3
    STG["environments/stage"] -->|key stage/| S3
    PRD["environments/prod"] -->|key prod/| S3
    SHARED["shared/ (ECR)"] -->|key shared/| S3
    DEV & STG & PRD & SHARED -.lock.-> DDB
```
