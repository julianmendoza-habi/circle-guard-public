"""
Locust load profile for CircleGuard public HTTP APIs.
Set environment variables (or host in UI):
  TARGET_FORM, TARGET_GATEWAY, TARGET_PROMOTION, TARGET_AUTH
"""
import os
import uuid

from locust import HttpUser, between, task


def _env(name: str, default: str) -> str:
    return os.environ.get(name, default).rstrip("/")


class CircleGuardUser(HttpUser):
    # Locust 2.x requires a base host even when every task uses absolute URLs (TARGET_* below).
    host = "http://127.0.0.1"

    wait_time = between(1, 3)

    def on_start(self) -> None:
        self.form_base = _env("TARGET_FORM", "http://localhost:8086")
        self.gateway_base = _env("TARGET_GATEWAY", "http://localhost:8087")
        self.promotion_base = _env("TARGET_PROMOTION", "http://localhost:8088")
        self.auth_base = _env("TARGET_AUTH", "http://localhost:8180")

    @task(3)
    def get_active_questionnaire(self) -> None:
        self.client.get(
            f"{self.form_base}/api/v1/questionnaires/active", name="/form/questionnaires/active"
        )

    @task(2)
    def list_buildings(self) -> None:
        self.client.get(
            f"{self.promotion_base}/api/v1/buildings", name="/promotion/buildings"
        )

    @task(2)
    def validate_gate_with_random_token(self) -> None:
        # Forces gateway JWT parsing / Redis branch under failure-heavy traffic (realistic retries).
        fake_token = "invalid." + uuid.uuid4().hex + ".token"
        self.client.post(
            f"{self.gateway_base}/api/v1/gate/validate",
            json={"token": fake_token},
            name="/gateway/gate/validate",
        )

    @task(1)
    def visitor_handoff(self) -> None:
        anon = str(uuid.uuid4())
        self.client.post(
            f"{self.auth_base}/api/v1/auth/visitor/handoff",
            json={"anonymousId": anon},
            name="/auth/visitor/handoff",
        )
