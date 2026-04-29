# Kubernetes deployment (CircleGuard)

## Apply order

1. **Namespaces**

   ```bash
   kubectl apply -f deploy/k8s/namespaces.yaml
   ```

2. **Infrastructure** (PostgreSQL, Redis, Neo4j, Kafka/ZooKeeper, OpenLDAP)

   ```bash
   kubectl apply -f deploy/k8s/infra/postgres-redis-neo4j.yaml
   kubectl apply -f deploy/k8s/infra/kafka-zookeeper.yaml
   kubectl apply -f deploy/k8s/infra/openldap.yaml
   ```

   Wait until pods are `Running` (especially Postgres init and Kafka startup).

3. **Microservices** (pick environment)

   ```bash
   kubectl apply -f deploy/k8s/apps/dev/microservices.yaml
   ```

   Replace `dev` with `stage` or `master` for other namespaces. Load Docker images into your cluster (e.g. `kind load docker-image ...`) when using Kind.

## Docker images

Build from repo root:

```bash
docker build -f docker/Dockerfile.service --build-arg SERVICE_DIR=circleguard-auth-service -t circleguard/auth-service:dev-latest .
```

Repeat for: `circleguard-identity-service`, `circleguard-form-service`, `circleguard-promotion-service`, `circleguard-notification-service`, `circleguard-gateway-service`.

Set image tags to match manifests (`dev-latest`, `stage-latest`, `prod-latest`) or override tags in Jenkins.

## Secrets

Demo manifests embed non-production passwords. For real deployments use sealed secrets or an external secret manager; rotate JWT and DB credentials.
