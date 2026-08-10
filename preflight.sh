#!/usr/bin/env bash
# Vaier pre-flight check. Run from the directory that holds docker-compose.yml.
# Validates the host, the downloaded files, the .env contents, and DNS state.
set -u

OK=$'\033[32m\xE2\x9C\x93\033[0m'
FAIL=$'\033[31m\xE2\x9C\x97\033[0m'
WARN=$'\033[33m\xE2\x9A\xA0\033[0m'
INFO=$'\033[34mi\033[0m'

errors=0
warnings=0
pass()    { printf '  %s %s\n' "$OK"   "$1"; }
fail()    { printf '  %s %s\n' "$FAIL" "$1"; errors=$((errors+1)); }
warn()    { printf '  %s %s\n' "$WARN" "$1"; warnings=$((warnings+1)); }
info()    { printf '  %s %s\n' "$INFO" "$1"; }
section() { printf '\n── %s ──\n' "$1"; }

REPO_RAW="https://raw.githubusercontent.com/getvaier/vaier/main"

section "Docker"
if command -v docker >/dev/null; then
  pass "docker installed: $(docker --version)"
else
  fail "docker not installed"
fi
if docker compose version >/dev/null 2>&1; then
  CV=$(docker compose version --short 2>/dev/null || echo "0.0.0")
  CV_MAJOR=${CV%%.*}
  CV_REST=${CV#*.}
  CV_MINOR=${CV_REST%%.*}
  if (( CV_MAJOR > 2 )) || (( CV_MAJOR == 2 && CV_MINOR >= 23 )); then
    pass "docker compose plugin v$CV (≥ 2.23 required for inline configs)"
  else
    fail "docker compose plugin v$CV is too old; v2.23+ required for the inline configs: block. Upgrade: curl -fsSL https://get.docker.com | sh"
  fi
else
  fail "docker compose plugin missing"
fi

section "Compose file"
if [[ -f docker-compose.yml ]]; then
  pass "docker-compose.yml present"
else
  fail "docker-compose.yml missing — curl -fsSL $REPO_RAW/docker-compose.yml -o docker-compose.yml"
fi

section ".env"
if [[ -f .env ]]; then
  pass ".env present"
  # shellcheck disable=SC1091
  set -a; . ./.env; set +a
  [[ -n "${VAIER_DOMAIN:-}" ]] && pass "VAIER_DOMAIN=$VAIER_DOMAIN" || fail "VAIER_DOMAIN is not set"
  [[ -n "${ACME_EMAIL:-}"  ]] && pass "ACME_EMAIL=$ACME_EMAIL"   || fail "ACME_EMAIL is not set"
  info "DNS: Vaier never writes records. Create one — *.\$VAIER_DOMAIN A <this server's public IP> — before first boot"
  PERM=$(stat -c '%a' .env 2>/dev/null || stat -f '%A' .env 2>/dev/null || echo "?")
  [[ "$PERM" == "600" ]] && pass ".env perms 600" || warn ".env perms $PERM (README suggests 600)"
else
  fail ".env missing — see Quick Start step 3"
fi

section "Kernel / WireGuard"
info "kernel $(uname -r)"
if grep -q '^wireguard ' /proc/modules 2>/dev/null; then
  pass "wireguard module already loaded"
elif modprobe -n wireguard >/dev/null 2>&1; then
  pass "wireguard module loadable"
else
  warn "wireguard module not present; modern kernels have it built-in and the lscr.io/wireguard image will load it on first start. If it fails after 'docker compose up', install kernel headers/wireguard-tools."
fi

section "EC2 IMDSv2"
PUB_IP=""
TOKEN=$(curl -fs --max-time 2 -X PUT "http://169.254.169.254/latest/api/token" \
  -H "X-aws-ec2-metadata-token-ttl-seconds: 60" 2>/dev/null || true)
if [[ -n "$TOKEN" ]]; then
  PUB_IP=$(curl -fs --max-time 2 -H "X-aws-ec2-metadata-token: $TOKEN" \
    http://169.254.169.254/latest/meta-data/public-ipv4 2>/dev/null || true)
  PUB_HOST=$(curl -fs --max-time 2 -H "X-aws-ec2-metadata-token: $TOKEN" \
    http://169.254.169.254/latest/meta-data/public-hostname 2>/dev/null || true)
  pass "IMDSv2 reachable from host (public-ipv4=$PUB_IP)"
  # Probe IMDS from inside the default Docker bridge to detect hop-limit=1.
  if command -v docker >/dev/null && docker info >/dev/null 2>&1; then
    DOCKER_PROBE=$(docker run --rm --network bridge curlimages/curl:8.10.1 \
      -fs --max-time 2 -X PUT "http://169.254.169.254/latest/api/token" \
      -H "X-aws-ec2-metadata-token-ttl-seconds: 60" 2>/dev/null || true)
    if [[ -n "$DOCKER_PROBE" ]]; then
      pass "IMDSv2 reachable from Docker bridge (hop-limit OK)"
    else
      warn "IMDSv2 NOT reachable from Docker bridge — instance metadata hop-limit is 1. Either raise it (aws ec2 modify-instance-metadata-options --http-put-response-hop-limit 2) or set VAIER_PUBLIC_IP=$PUB_IP in .env"
    fi
  fi
else
  warn "IMDSv2 not reachable; Vaier cannot auto-detect public IP. Set VAIER_PUBLIC_IP in .env."
fi

section "Host port conflicts"
for p in 80 443; do
  if ss -ltnH "sport = :$p" 2>/dev/null | grep -q .; then
    fail "TCP $p already bound by another process"
  else
    pass "TCP $p free"
  fi
done
if ss -lunH "sport = :51820" 2>/dev/null | grep -q .; then
  fail "UDP 51820 already bound by another process"
else
  pass "UDP 51820 free"
fi

section "Public DNS"
if ! command -v dig >/dev/null; then
  info "dig not installed; skipping DNS checks (apt install dnsutils  OR  dnf install bind-utils)"
elif [[ -n "${VAIER_DOMAIN:-}" ]]; then
  NS=$(dig +short NS "${VAIER_DOMAIN}" @1.1.1.1 2>/dev/null | tr -d '\r')
  if [[ -z "$NS" ]]; then
    fail "${VAIER_DOMAIN} has no public NS records — registrar delegation not in place"
  else
    pass "${VAIER_DOMAIN} has NS records: $(echo "$NS" | tr '\n' ' ')"
  fi
  # Probe the wildcard TWO labels deep, the same way Vaier does at boot. Vaier publishes
  # machine-qualified names (<service>.<machine>.<domain>) and a wildcard matches by closest
  # encloser (RFC 4592): a one-label probe is answered even on a zone where every
  # machine-qualified service is dead. Both labels are random so no resolver can have them cached.
  PROBE="vaierchk$RANDOM.vaierchk$RANDOM.${VAIER_DOMAIN}"
  WILD=$(dig +short A "$PROBE" @1.1.1.1 2>/dev/null | tr -d '\r')
  if [[ -z "$WILD" ]]; then
    fail "*.${VAIER_DOMAIN} does not resolve — create one record, *.${VAIER_DOMAIN} A ${PUB_IP:-<the public IP of this server>}, BEFORE 'docker compose up'"
  elif [[ -n "$PUB_IP" ]] && ! echo "$WILD" | grep -qx "$PUB_IP"; then
    fail "*.${VAIER_DOMAIN} resolves to $(echo "$WILD" | tr '\n' ' ') but this server is $PUB_IP — point the record here"
  else
    pass "*.${VAIER_DOMAIN} resolves → $(echo "$WILD" | tr '\n' ' ')"
  fi
fi

section "Public ingress sanity (best effort)"
if [[ -n "$PUB_IP" ]]; then
  EXT=$(curl -fs --max-time 5 https://icanhazip.com 2>/dev/null | tr -d '\n')
  if [[ -n "$EXT" && "$EXT" == "$PUB_IP" ]]; then
    pass "outbound IP matches IMDS public IP ($EXT)"
  elif [[ -n "$EXT" ]]; then
    warn "outbound IP ($EXT) ≠ IMDS public IP ($PUB_IP); behind NAT or instance has multiple addresses"
  fi
  info "Inbound reachability must be tested from OUTSIDE. From your laptop:"
  info "  nc -vz $PUB_IP 80 && nc -vz $PUB_IP 443"
  info "  nmap -sU -p 51820 $PUB_IP   # UDP needs nmap; nc -u won't tell you anything useful"
fi

section "Summary"
if (( errors == 0 && warnings == 0 )); then
  printf '%s All checks passed. Run: docker compose up -d\n' "$OK"
elif (( errors == 0 )); then
  printf '%s %d warning(s); review above before first boot.\n' "$WARN" "$warnings"
else
  printf '%s %d error(s), %d warning(s). Fix the errors before docker compose up -d.\n' "$FAIL" "$errors" "$warnings"
  exit 1
fi
