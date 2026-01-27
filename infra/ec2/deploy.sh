#!/usr/bin/env bash
# One-shot AURA deploy for a fresh Ubuntu 22.04 EC2 instance.
# Usage (on the EC2 host):
#   curl -fsSL https://raw.githubusercontent.com/<you>/AURA/main/infra/ec2/deploy.sh | bash -s -- \
#       --repo https://github.com/<you>/AURA.git \
#       --gemini-key <key> \
#       --domain aura.example.com   # optional -- omit to skip TLS

set -euo pipefail

REPO=""
GEMINI_KEY=""
DOMAIN=""
BRANCH="main"
APP_DIR="/opt/aura"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo)        REPO="$2"; shift 2 ;;
    --gemini-key)  GEMINI_KEY="$2"; shift 2 ;;
    --domain)      DOMAIN="$2"; shift 2 ;;
    --branch)      BRANCH="$2"; shift 2 ;;
    --app-dir)     APP_DIR="$2"; shift 2 ;;
    *) echo "Unknown flag: $1"; exit 1 ;;
  esac
done

if [[ -z "$REPO" ]]; then
  echo "ERROR: --repo is required"
  exit 1
fi

echo "==> Installing Docker + compose plugin"
if ! command -v docker >/dev/null 2>&1; then
  sudo apt-get update -y
  sudo apt-get install -y ca-certificates curl gnupg lsb-release git
  sudo install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg | \
    sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
    https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" | \
    sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
  sudo apt-get update -y
  sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  sudo systemctl enable --now docker
  sudo usermod -aG docker "$USER" || true
fi

echo "==> Cloning repo to $APP_DIR"
sudo mkdir -p "$APP_DIR"
sudo chown "$USER":"$USER" "$APP_DIR"
if [[ -d "$APP_DIR/.git" ]]; then
  git -C "$APP_DIR" fetch --all --prune
  git -C "$APP_DIR" checkout "$BRANCH"
  git -C "$APP_DIR" pull --ff-only
else
  git clone --branch "$BRANCH" "$REPO" "$APP_DIR"
fi

echo "==> Writing .env"
JWT_SECRET="$(openssl rand -hex 32)"
cat > "$APP_DIR/.env" <<EOF
AURA_GEMINI_API_KEY=${GEMINI_KEY}
AURA_GEMINI_FAST_MODEL=gemini-2.0-flash
AURA_GEMINI_REASONING_MODEL=gemini-2.0-pro
POSTGRES_DB=aura
POSTGRES_USER=aura
POSTGRES_PASSWORD=$(openssl rand -hex 16)
AURA_JWT_SECRET=${JWT_SECRET}
AURA_CORS_ORIGINS=${DOMAIN:+https://$DOMAIN,}http://localhost:3000
AURA_RATE_LIMIT_RPS=40
NEXT_PUBLIC_API_URL=${DOMAIN:+https://$DOMAIN}
NEXT_PUBLIC_WS_URL=${DOMAIN:+wss://$DOMAIN}
EOF

if [[ -z "$DOMAIN" ]]; then
  # Bare EC2 IP mode -- point the frontend at :8081 directly.
  IP=$(curl -fsS http://169.254.169.254/latest/meta-data/public-ipv4 || hostname -I | awk '{print $1}')
  sed -i "s|NEXT_PUBLIC_API_URL=.*|NEXT_PUBLIC_API_URL=http://$IP:8081|" "$APP_DIR/.env"
  sed -i "s|NEXT_PUBLIC_WS_URL=.*|NEXT_PUBLIC_WS_URL=ws://$IP:8081|" "$APP_DIR/.env"
fi

echo "==> docker compose up -d"
cd "$APP_DIR"
sudo docker compose --env-file .env up -d --build

if [[ -n "$DOMAIN" ]]; then
  echo "==> Setting up nginx + Let's Encrypt for $DOMAIN"
  sudo apt-get install -y nginx certbot python3-certbot-nginx
  sudo cp infra/nginx/nginx.conf /etc/nginx/sites-available/aura
  sudo ln -sf /etc/nginx/sites-available/aura /etc/nginx/sites-enabled/aura
  sudo rm -f /etc/nginx/sites-enabled/default
  sudo nginx -t
  sudo systemctl reload nginx
  sudo certbot --nginx --non-interactive --agree-tos --redirect \
      -d "$DOMAIN" --register-unsafely-without-email || \
      echo "certbot failed; run manually once DNS resolves."
fi

echo ""
echo "==> Deploy finished."
echo "    Gateway:   http://$(hostname -I | awk '{print $1}'):8081/health"
echo "    Frontend:  http://$(hostname -I | awk '{print $1}'):3000"
[[ -n "$DOMAIN" ]] && echo "    Public:    https://$DOMAIN"
echo ""
echo "    Seed 50k KB chunks:  cd $APP_DIR && ./scripts/seed-knowledge.sh"
