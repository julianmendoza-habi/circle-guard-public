# Jenkins pipelines (CircleGuard)

Declarative pipeline definitions live alongside the code:

| File | Purpose |
|------|---------|
| [`Jenkinsfile.dev.groovy`](Jenkinsfile.dev.groovy) | Dev: Gradle tests, Docker builds, deploy `circleguard-dev` |
| [`Jenkinsfile.stage.groovy`](Jenkinsfile.stage.groovy) | Stage: `-Pintegration`, deploy `circleguard-stage`, Locust HTML/CSV artifacts |
| [`Jenkinsfile.master.groovy`](Jenkinsfile.master.groovy) | Master: deploy `circleguard-master`, optional E2E, release notes |

Create three Multibranch or Pipeline jobs in Jenkins pointing to this repo and the matching script path. Configure Docker registry credentials and `kubectl` context as required.
