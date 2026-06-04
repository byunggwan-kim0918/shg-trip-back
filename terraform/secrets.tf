# ── FSQ 토큰 ─────────────────────────────────────────────────────────────────
resource "aws_secretsmanager_secret" "fsq_token" {
  name                    = "${var.project}/fsq-access-token"
  description             = "Foursquare Places Portal 액세스 토큰"
  recovery_window_in_days = 7

  tags = {
    Project = var.project
    Env     = var.env
  }
}

resource "aws_secretsmanager_secret_version" "fsq_token" {
  secret_id     = aws_secretsmanager_secret.fsq_token.id
  secret_string = var.fsq_access_token
}
