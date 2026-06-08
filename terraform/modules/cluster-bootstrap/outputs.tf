output "app_namespace" {
  description = "Application namespace name."
  value       = kubernetes_namespace.app.metadata[0].name
}

output "infra_namespace" {
  description = "Infra namespace name."
  value       = kubernetes_namespace.infra.metadata[0].name
}

output "runtime_configmap" {
  description = "Runtime ConfigMap name."
  value       = kubernetes_config_map.runtime.metadata[0].name
}
