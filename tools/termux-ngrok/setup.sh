#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

DEFAULT_REPO="imranperatama/nexora-android-terbaru"
GH_REPO="$DEFAULT_REPO"
GH_BRANCH="main"
CONFIG_DIR="$HOME/.config/nexora"
BIN_DIR="$HOME/.local/bin"
BOOT_DIR="$HOME/.termux/boot"

printf '\nNEXORA — setup ngrok auto-discovery\n'
printf 'Repo discovery: %s (branch %s)\n' "$GH_REPO" "$GH_BRANCH"
printf 'Token hanya disimpan lokal di Termux; jangan unggah atau kirim ke siapa pun.\n\n'
command -v pkg >/dev/null 2>&1 || { echo 'Jalankan skrip ini dari aplikasi Termux yang resmi.'; exit 1; }

pkg update
pkg install -y curl jq coreutils tar util-linux
mkdir -p "$CONFIG_DIR" "$BIN_DIR" "$BOOT_DIR"
chmod 700 "$CONFIG_DIR"

install_ngrok() {
  if command -v ngrok >/dev/null 2>&1; then
    echo "ngrok ditemukan: $(command -v ngrok)"
    return 0
  fi
  local arch url tmpdir
  case "$(uname -m)" in
    aarch64|arm64) arch=arm64 ;;
    x86_64|amd64) arch=amd64 ;;
    *) echo "Arsitektur $(uname -m) belum didukung installer ini."; return 1 ;;
  esac
  url="https://bin.equinox.io/c/bNyj1mQVY4c/ngrok-v3-stable-linux-${arch}.tgz"
  tmpdir="$(mktemp -d)"
  echo "Mengunduh ngrok v3 resmi untuk Linux ${arch}..."
  if ! curl -fL --retry 2 --connect-timeout 15 "$url" -o "$tmpdir/ngrok.tgz"; then
    rm -rf "$tmpdir"
    echo "Unduh ngrok gagal. Pasang ngrok dari sumber resmi lalu jalankan setup.sh lagi."
    return 1
  fi
  if ! tar -xzf "$tmpdir/ngrok.tgz" -C "$tmpdir" ngrok; then
    rm -rf "$tmpdir"
    echo "Arsip ngrok tidak valid."
    return 1
  fi
  install -m 700 "$tmpdir/ngrok" "$BIN_DIR/ngrok"
  rm -rf "$tmpdir"
  export PATH="$BIN_DIR:$PATH"
  if ! "$BIN_DIR/ngrok" version; then
    echo "Biner Linux tidak dapat berjalan di lingkungan Termux pada perangkat ini."
    echo "Hapus $BIN_DIR/ngrok, pasang biner yang kompatibel dari sumber resmi ngrok, lalu ulangi setup."
    return 1
  fi
}

export PATH="$BIN_DIR:$PATH"
install_ngrok
export PATH="$BIN_DIR:$PATH"

read -r -s -p 'Ngrok authtoken (dari dashboard akun ngrok): ' NGROK_AUTHTOKEN
echo
if [[ -z "$NGROK_AUTHTOKEN" ]]; then echo 'Token kosong; setup dibatalkan.'; exit 1; fi
if ! ngrok config add-authtoken "$NGROK_AUTHTOKEN" >/dev/null; then
  unset NGROK_AUTHTOKEN
  echo 'Ngrok menolak token. Periksa token di dashboard.'
  exit 1
fi
unset NGROK_AUTHTOKEN
NGROK_CONFIG="$HOME/.config/ngrok/ngrok.yml"
[[ -f "$NGROK_CONFIG" ]] && chmod 600 "$NGROK_CONFIG" || true

read -r -s -p 'GitHub fine-grained token (Contents: Read and write untuk repo di atas): ' GH_TOKEN
echo
if [[ -z "$GH_TOKEN" ]]; then echo 'Token kosong; setup dibatalkan.'; exit 1; fi
# Bash %q stores values so they can safely be sourced later.
{
  printf 'GH_REPO=%q\n' "$GH_REPO"
  printf 'GH_BRANCH=%q\n' "$GH_BRANCH"
  printf 'GH_TOKEN=%q\n' "$GH_TOKEN"
} > "$CONFIG_DIR/ngrok.env"
chmod 600 "$CONFIG_DIR/ngrok.env"
unset GH_TOKEN

# Check that GitHub accepts the token and repository is reachable.
source "$CONFIG_DIR/ngrok.env"
API="https://api.github.com/repos/${GH_REPO}/contents/tunnel.json?ref=${GH_BRANCH}"
code="$(curl -sS -o "$CONFIG_DIR/github-check.json" -w '%{http_code}' --max-time 15 \
  "$API" -H 'Accept: application/vnd.github+json' -H 'X-GitHub-Api-Version: 2022-11-28' \
  -H "Authorization: Bearer ${GH_TOKEN}" || true)"
if [[ "$code" != '200' && "$code" != '404' ]]; then
  echo "GitHub API belum bisa diakses (HTTP ${code}). Periksa nama repo/token dan izin Contents."
  echo "Konfigurasi token tersimpan privat di $CONFIG_DIR/ngrok.env; jalankan setup setelah memperbaiki izin."
  exit 1
fi
rm -f "$CONFIG_DIR/github-check.json"

WATCHER="$(cd "$(dirname "$0")" && pwd)/nexora-ngrok-watch.sh"
if [[ ! -f "$WATCHER" ]]; then echo 'nexora-ngrok-watch.sh tidak ditemukan. Ekstrak semua isi ZIP ke folder yang sama.'; exit 1; fi
install -m 700 "$WATCHER" "$BIN_DIR/nexora-ngrok-watch.sh"

cat > "$BOOT_DIR/nexora-ngrok" <<'BOOT'
#!/data/data/com.termux/files/usr/bin/bash
# Termux:Boot starts the watcher. It waits until NEXORA's local server is alive.
termux-wake-lock >/dev/null 2>&1 || true
export PATH="$HOME/.local/bin:$PATH"
nohup "$HOME/.local/bin/nexora-ngrok-watch.sh" >> "$HOME/.config/nexora/boot.log" 2>&1 &
BOOT
chmod 700 "$BOOT_DIR/nexora-ngrok"

echo
printf 'Setup awal selesai. Sekarang jalankan server di NEXORA HP pertama.\n'
printf 'Helper memerlukan akses Contents: Read and write ke repo %s agar dapat memperbarui tunnel.json.\n' "$GH_REPO"
printf 'Pasang Termux:Boot secara terpisah bila ingin helper dimulai setelah HP restart.\n'
read -r -p 'Mulai helper sekarang? [y/N]: ' RUN_NOW
if [[ "$RUN_NOW" == 'y' || "$RUN_NOW" == 'Y' ]]; then
  termux-wake-lock >/dev/null 2>&1 || true
  export PATH="$BIN_DIR:$PATH"
  nohup "$BIN_DIR/nexora-ngrok-watch.sh" >> "$CONFIG_DIR/boot.log" 2>&1 &
  echo "Helper dijalankan (PID $!). Buka log dengan: tail -f ~/.config/nexora/boot.log"
else
  echo 'Nanti jalankan: ~/.local/bin/nexora-ngrok-watch.sh'
fi
