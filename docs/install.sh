#!/usr/bin/env bash

set -e

# ─── Config ───────────────────────────────────────────────────────────
repo="kdroidFilter/Nucleus"
app_name="nucleusdemo"

# ─── Colors & Symbols ────────────────────────────────────────────────
BOLD='\033[1m'
DIM='\033[2m'
RESET='\033[0m'
GREEN='\033[1;32m'
CYAN='\033[1;36m'
YELLOW='\033[1;33m'
RED='\033[1;31m'
MAGENTA='\033[1;35m'
BLUE='\033[1;34m'
WHITE='\033[1;37m'
BG_GREEN='\033[42m'
BG_DARK='\033[48;5;236m'

CHECK="${GREEN}✔${RESET}"
CROSS="${RED}✖${RESET}"
ARROW="${CYAN}➜${RESET}"
SPARKLE="${MAGENTA}✦${RESET}"

# ─── Banner ───────────────────────────────────────────────────────────
print_banner() {
  echo ""
  echo -e "${CYAN}${BOLD}"
  echo "    ╔╗╔╦ ╦╔═╗╦  ╔═╗╦ ╦╔═╗"
  echo "    ║║║║ ║║  ║  ║╣ ║ ║╚═╗"
  echo "    ╝╚╝╚═╝╚═╝╩═╝╚═╝╚═╝╚═╝"
  echo -e "${RESET}"
  echo -e "    ${DIM}Installer for macOS${RESET}"
  echo ""
}

# ─── Helpers ──────────────────────────────────────────────────────────
step() {
  echo -e "  ${ARROW} ${BOLD}$1${RESET}"
}

success() {
  echo -e "  ${CHECK} ${GREEN}$1${RESET}"
}

fail() {
  echo -e "  ${CROSS} ${RED}$1${RESET}"
  exit 1
}

spin() {
  local pid=$1
  local msg=$2
  local frames=("⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏")
  local i=0

  tput civis 2>/dev/null || true
  while kill -0 "$pid" 2>/dev/null; do
    printf "\r  ${CYAN}${frames[$i]}${RESET} ${DIM}%s${RESET}" "$msg"
    i=$(( (i + 1) % ${#frames[@]} ))
    sleep 0.08
  done
  printf "\r\033[2K"
  tput cnorm 2>/dev/null || true
}

download_with_progress() {
  local url="$1"
  local output="$2"
  local bar_width=40

  # Get total file size via HEAD request (follow redirects)
  local total_size
  total_size=$(curl -sIL "$url" | grep -i '^content-length:' | tail -1 | tr -dc '0-9')

  if [ -z "$total_size" ] || [ "$total_size" -eq 0 ]; then
    # Fallback: download with spinner if size unknown
    curl -sL --output "$output" "$url" &
    spin $! "Downloading"
    return
  fi

  # Download silently in background
  curl -sL --output "$output" "$url" &
  local curl_pid=$!

  tput civis 2>/dev/null || true

  while kill -0 "$curl_pid" 2>/dev/null; do
    if [ -f "$output" ]; then
      local current_size
      current_size=$(stat -f%z "$output" 2>/dev/null || echo 0)
      local pct=$((current_size * 100 / total_size))
      [ "$pct" -gt 100 ] && pct=100
      local filled=$((pct * bar_width / 100))
      local empty=$((bar_width - filled))
      local bar=""
      for ((j=0; j<filled; j++)); do bar+="█"; done
      for ((j=0; j<empty; j++)); do bar+="░"; done
      local mb_done mb_total
      mb_done=$(echo "scale=1; $current_size / 1048576" | bc)
      mb_total=$(echo "scale=1; $total_size / 1048576" | bc)
      printf "\r  \033[1;35m✦\033[0m \033[2mDownloading\033[0m  \033[1;34m%s\033[0m  \033[1;37m%3d%%\033[0m  \033[2m(%s / %s MB)\033[0m" "$bar" "$pct" "$mb_done" "$mb_total"
    fi
    sleep 0.15
  done

  # Final 100%
  local bar=""
  for ((j=0; j<bar_width; j++)); do bar+="█"; done
  local mb_total
  mb_total=$(echo "scale=1; $total_size / 1048576" | bc)
  printf "\r  \033[1;35m✦\033[0m \033[2mDownloading\033[0m  \033[1;34m%s\033[0m  \033[1;37m100%%\033[0m  \033[2m(%s / %s MB)\033[0m" "$bar" "$mb_total" "$mb_total"
  echo ""

  tput cnorm 2>/dev/null || true
  wait "$curl_pid"
}

# ─── Main ─────────────────────────────────────────────────────────────
print_banner

# Resolve latest version
step "Fetching latest release ..."
version=$(curl -sI "https://github.com/${repo}/releases/latest" | grep -i '^location:' | sed 's/.*tag\///' | tr -d '\r\n')

if [ -z "$version" ]; then
  fail "Could not determine latest version"
fi

version_number="${version#v}"
success "Found ${BOLD}${YELLOW}${version}${RESET}"

# Detect architecture
arch=$(arch)
if [[ "$arch" == "i386" ]]; then
  mac_zip_url="https://github.com/${repo}/releases/download/${version}/${app_name}-${version_number}-mac-x64.zip"
  arch_label="Intel (x64)"
elif [[ "$arch" == "arm64" ]]; then
  mac_zip_url="https://github.com/${repo}/releases/download/${version}/${app_name}-${version_number}-mac-arm64.zip"
  arch_label="Apple Silicon (arm64)"
else
  fail "Unsupported CPU architecture: $arch"
fi

success "Architecture: ${BOLD}${arch_label}${RESET}"
echo ""

# Temp directory
tmpdir="$(mktemp -d -t nucleus-install)"
tmpfile="$tmpdir/app.zip"

cleanup() {
  cd "$HOME" || true
  [ -e "$tmpfile" ] && rm -f "$tmpfile"
  rmdir "$tmpdir" 2>/dev/null || true
}
trap cleanup EXIT

# Download
step "Downloading ${YELLOW}${app_name}${RESET} ${DIM}(${version_number})${RESET} ..."
download_with_progress "$mac_zip_url" "$tmpfile"
success "Download complete"
echo ""

# Install location
if [ -w /Applications ]; then
  install_dir="/Applications"
else
  install_dir="$HOME/Applications"
  [ ! -d "$install_dir" ] && mkdir "$install_dir"
fi

if [ -d "${install_dir}/${app_name}.app" ]; then
  step "Replacing existing installation ..."
  rm -rf "${install_dir}/${app_name}.app"
fi

# Extract
step "Extracting to ${DIM}${install_dir}${RESET} ..."
(cd "$install_dir" && ditto -x -k "$tmpfile" .) &
spin $! "Unpacking application bundle"
success "Installed to ${BOLD}${install_dir}/${app_name}.app${RESET}"

# Remove quarantine
if command -v xattr >/dev/null 2>&1; then
  xattr -r -d com.apple.quarantine "${install_dir}/${app_name}.app" 2>/dev/null || true
fi

# Launch
echo ""
step "Launching ${YELLOW}${app_name}${RESET} ..."
open "${install_dir}/${app_name}.app"
success "Application started"

# Done
echo ""
echo -e "  ${SPARKLE}${SPARKLE}${SPARKLE} ${GREEN}${BOLD}All done!${RESET} ${SPARKLE}${SPARKLE}${SPARKLE}"
echo ""

cleanup
exit 0
