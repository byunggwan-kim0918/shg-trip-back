# ── FSQ 토큰 ─────────────────────────────────────────────────────────────────
# 30일마다 만료 — 갱신은 terraform/scripts/set-secret.sh fsq
resource "aws_secretsmanager_secret" "fsq_token" {
  name                    = "${var.project}/fsq-access-token"
  description             = "Foursquare Places Portal 액세스 토큰"
  recovery_window_in_days = 7

  tags = {
    Project = var.project
    Env     = var.env
  }
}

removed {
  from = aws_secretsmanager_secret_version.fsq_token

  lifecycle {
    destroy = false
  }
}
