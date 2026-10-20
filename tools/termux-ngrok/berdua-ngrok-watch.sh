#!/data/data/com.termux/files/usr/bin/bash
set -u

CONFIG="$HOME/.config/nexora/ngrok.env"
if [[ ! -f "$CONFIG" ]]; then echo "Konfigurasi $CONFIG belum ada. Jalankan setup.sh dahulu."; exit 2; fi
if [[ "${1:-}" != '--locked' ]]; then
  mkdir -p "$HOME/.config/nexora"
  exec flock -n "$HOME/.config/nexora/ngrok.lock" "$0" --locked || {
    echo 'NEXORA ngrok helper sudah berjalan.'
    exit 0
  }
fi
# shellcheck source=/dev/null
source "$CONFIG"
export PATH="$HOME/.local/bin:$PATH"

API="https://api.github.com/repos/${GH_REPO}/contents/tunnel.json"
LOG_DIR="$HOME/.config/nexora"
NGROK_LOG="$LOG_DIR/ngrok.log"
GH_LOG="$LOG_DIR/github-update.log"
mkdir -p "$LOG_DIR"
chmod 700 "$LOG_DIR"
TUNNEL_PID=''
LAST_STATE=''

local_server_healthy() {
  curl -fsS --max-time 2 'http://127.0.0.1:8787/api/discovery-health' >/dev/null 2>&1
}

current_https_url() {
  curl -fsS --max-time 2 'http://127.0.0.1:4040/api/tunnels' 2>/dev/null \
    | jq -r '[.tunnels[]? | select(.proto == "https") | .public_url][0] // empty' 2>/dev/null || true
}

publish_state() {
  local active_url="$1" online="$2" now content encoded response sha payload status tmp
  now="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  content="$(jq -cn --arg url "$active_url" --argjson online "$online" --arg updatedAt "$now" \
    '{url:$url,online:$online,updatedAt:$updatedAt}')"
  encoded="$(printf '%s' "$content" | base64 | tr -d '\n')"
  response="$(curl -sS --max-time 15 "$API?ref=$GH_BRANCH" \
    -H 'Accept: application/vnd.github+json' -H 'X-GitHub-Api-Version: 2022-11-28' \
    -H "Authorization: Bearer $GH_TOKEN" 2>>"$GH_LOG" || true)"
  sha="$(printf '%s' "$response" | jq -r '.sha // empty' 2>/dev/null || true)"
  payload="$(jq -cn --arg message 'Update NEXORA ngrok status' --arg content "$encoded" \
    --arg branch "$GH_BRANCH" --arg sha "$sha" \
    '{message:$message,content:$content,branch:$branch} + (if $sha != "" then {sha:$sha} else {} end)')"
  tmp="$(mktemp "$LOG_DIR/gh.XXXXXX")"
  status="$(curl -sS --max-time 20 -o "$tmp" -w '%{http_code}' -X PUT "$API" \
    -H 'Accept: application/vnd.github+json' -H 'X-GitHub-Api-Version: 2022-11-28' \
    -H "Authorization: Bearer $GH_TOKEN" -H 'Content-Type: application/json' \
    --data "$payload" 2>>"$GH_LOG" || true)"
  if [[ "$status" == '200' || "$status" == '201' ]]; then
    echo "[$now] discovery updated: online=$online url=${active_url:-none}"
    rm -f "$tmp"
    return 0
  fi
  echo "[$now] GitHub update failed (HTTP ${status:-unknown}); periksa izin Contents dan konflik SHA." | tee -a "$GH_LOG"
  cat "$tmp" >> "$GH_LOG" 2>/dev/null || true
  rm -f "$tmp"
  return 1
}

stop_tunnel() {
  if [[ -n "$TUNNEL_PID" ]] && kill -0 "$TUNNEL_PID" 2>/dev/null; then
    kill "$TUNNEL_PID" 2>/dev/null || true
    wait "$TUNNEL_PID" 2>/dev/null || true
  fi
  TUNNEL_PID=''
}

cleanup() {
  stop_tunnel
  publish_state '' false || true
  exit 0
}
trap cleanup INT TERM EXIT

echo "NEXORA ngrok helper aktif; memantau server lokal port 8787."
while true; do
  if ! local_server_healthy; then
    stop_tunnel
    state='offline:'
    if [[ "$state" != "$LAST_STATE" ]]; then
      publish_state '' false || true
      LAST_STATE="$state"
    fi
    sleep 4
    continue
  fi

  if [[ -z "$TUNNEL_PID" ]] || ! kill -0 "$TUNNEL_PID" 2>/dev/null; then
    : > "$NGROK_LOG"
    echo "[$(date)] Server NEXORA aktif; memulai ngrok http 8787."
    ngrok http 8787 --log=stdout >"$NGROK_LOG" 2>&1 &
    TUNNEL_PID=$!
  fi

  URL="$(current_https_url)"
  if [[ "$URL" == https://* && "$URL" != *' '* ]]; then
    state="online:$URL"
    if [[ "$state" != "$LAST_STATE" ]]; then
      publish_state "$URL" true || true
      LAST_STATE="$state"
    fi
  else
    state='starting:'
    if [[ "$state" != "$LAST_STATE" ]]; then
      echo "[$(date)] Menunggu endpoint ngrok muncul..."
      publish_state '' false || true
      LAST_STATE="$state"
    fi
  fi
  sleep 4
done
