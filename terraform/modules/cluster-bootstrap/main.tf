terraform {
  required_providers {
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = "~> 2.31"
    }
  }
}

# This module owns the seam between Terraform-provisioned managed services and the
# existing `deploy/k8s/**` manifests. It creates the namespaces plus the runtime
# ConfigMap/Secret that the app Deployments read via envFrom — so pointing the apps
# at RDS/ElastiCache/MSK requires NO change to the application manifests, only the
# values rendered here.
#
# In-cluster stateful infra (Neo4j, OpenLDAP, and optionally Kafka/Zookeeper) is
# applied from deploy/k8s/infra/* with kubectl after this module runs; the DNS names
# below match those manifests.

resource "kubernetes_namespace" "infra" {
  metadata {
    name = var.infra_namespace
  }
}

resource "kubernetes_namespace" "app" {
  metadata {
    name = var.app_namespace
  }
}

resource "kubernetes_config_map" "runtime" {
  metadata {
    name      = var.runtime_configmap_name
    namespace = kubernetes_namespace.app.metadata[0].name
  }

  data = {
    SPRING_KAFKA_BOOTSTRAP_SERVERS = var.kafka_bootstrap_servers
    POSTGRES_HOST                  = var.postgres_host
    REDIS_HOST                     = var.redis_host
    NEO4J_URI                      = var.neo4j_uri
    LDAP_PRIMARY_URL               = var.ldap_primary_url
  }
}

resource "kubernetes_secret" "shared" {
  metadata {
    name      = "circleguard-shared-secret"
    namespace = kubernetes_namespace.app.metadata[0].name
  }

  type = "Opaque"

  data = {
    datasource-password = var.datasource_password
    jwt-secret          = var.jwt_secret
    qr-secret           = var.qr_secret
  }
}
