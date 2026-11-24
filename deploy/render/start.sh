#!/bin/bash
# Starts the Howlers API and nginx in one container (deploy/render/Dockerfile). Exits when either process stops,
# so the host restarts the container.
set -euo pipefail

# --- Secrets. The prod profile refuses to start without them (ProductionSafetyCheck). Any value set explicitly wins;
# otherwise each one is derived from HOWLERS_SECRET_SEED (Render generates it once and keeps it), in the exact format
# the API expects. Changing the seed logs everyone out and makes saved two-factor setups unreadable.
derive() { printf '%s' "${HOWLERS_SECRET_SEED}:$1" | openssl dgst -sha256 -binary | openssl base64 -A; }
if [ -n "${HOWLERS_SECRET_SEED:-}" ]; then
  export JWT_SECRET="${JWT_SECRET:-$(derive jwt)}"                                  # base64 of 32 bytes
  export ACCOUNT_SECRETS_KEY="${ACCOUNT_SECRETS_KEY:-$(derive account-secrets)}"    # base64 of exactly 32 bytes
  export FILE_VISITOR_SALT="${FILE_VISITOR_SALT:-$(derive file-visitor-salt)}"
  export FUNDING_DEMO_WEBHOOK_SECRET="${FUNDING_DEMO_WEBHOOK_SECRET:-$(derive funding-demo-webhook)}"
fi

# --- Public address. Render sets RENDER_EXTERNAL_URL (https://<name>.onrender.com); a custom domain can override it.
if [ -n "${RENDER_EXTERNAL_URL:-}" ]; then
  export APP_PUBLIC_URL="${APP_PUBLIC_URL:-$RENDER_EXTERNAL_URL}"
fi
if [ -n "${APP_PUBLIC_URL:-}" ]; then
  export CORS_ALLOWED_ORIGINS="${CORS_ALLOWED_ORIGINS:-$APP_PUBLIC_URL}"
fi
export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-prod}"

# --- nginx listens on the host's port; the API stays on 8080 inside the container.
PUBLIC_PORT="${PORT:-10000}"
sed "s/__PORT__/${PUBLIC_PORT}/" /etc/nginx/howlers.conf.template > /etc/nginx/conf.d/howlers.conf

# Tuned for a 512 MB instance: small heap, serial GC, C1 only (fast start-up, low memory). Override with JAVA_OPTS.
JAVA_OPTS="${JAVA_OPTS:--Xmx256m -Xss512k -XX:MaxMetaspaceSize=200m -XX:ReservedCodeCacheSize=48m -XX:MaxDirectMemorySize=64m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:+ExitOnOutOfMemoryError}"
export SERVER_TOMCAT_THREADS_MAX="${SERVER_TOMCAT_THREADS_MAX:-40}"

# shellcheck disable=SC2086
PORT=8080 java $JAVA_OPTS -jar /app/app.jar &
API_PID=$!
nginx -g 'daemon off;' &
NGINX_PID=$!

trap 'kill -TERM "$API_PID" "$NGINX_PID" 2>/dev/null || true' TERM INT
set +e
wait -n "$API_PID" "$NGINX_PID"
STATUS=$?
kill -TERM "$API_PID" "$NGINX_PID" 2>/dev/null
wait
exit "$STATUS"
