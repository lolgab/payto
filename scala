#!/usr/bin/env sh

# This is the launcher script of Scala CLI (https://github.com/VirtusLab/scala-cli).
# This script downloads and runs the Scala CLI version set by SCALA_CLI_VERSION below.
#
# Download the latest version of this script at https://github.com/VirtusLab/scala-cli/raw/main/scala-cli.sh

set -eu

echoerr() {
  echo "$@" >&2
}

SCALA_CLI_VERSION="1.14.0"

GH_ORG="VirtusLab"
GH_NAME="scala-cli"

if [ "$SCALA_CLI_VERSION" = "nightly" ]; then
  TAG="nightly"
else
  TAG="v$SCALA_CLI_VERSION"
fi

if [ "$(expr substr "$(uname -s)" 1 5 2>/dev/null)" = "Linux" ]; then
  arch=$(uname -m)
  if [ "$arch" = "aarch64" ]; then
    SCALA_CLI_URL="https://github.com/$GH_ORG/$GH_NAME/releases/download/$TAG/scala-cli-aarch64-pc-linux-static-sdk.zip"
  elif [ "$arch" = "x86_64" ]; then
    SCALA_CLI_URL="https://github.com/$GH_ORG/$GH_NAME/releases/download/$TAG/scala-cli-x86_64-pc-linux-static.gz"
  else
    echoerr "scala-cli is not supported on $arch"
    exit 2
  fi
  CACHE_BASE="$HOME/.cache/coursier/v1"
elif [ "$(uname)" = "Darwin" ]; then
  arch=$(uname -m)
  CACHE_BASE="$HOME/Library/Caches/Coursier/v1"
  if [ "$arch" = "x86_64" ]; then
    SCALA_CLI_URL="https://github.com/$GH_ORG/$GH_NAME/releases/download/$TAG/scala-cli-x86_64-apple-darwin.gz"
  elif [ "$arch" = "arm64" ]; then
    SCALA_CLI_URL="https://github.com/$GH_ORG/$GH_NAME/releases/download/$TAG/scala-cli-aarch64-apple-darwin.gz"
  else
    echoerr "scala-cli is not supported on $arch"
    exit 2
  fi
else
  echo "This standalone scala-cli launcher is supported only in Linux and macOS. If you are using Windows, please use the dedicated launcher scala-cli.bat"
  exit 1
fi

CACHE_DEST="$CACHE_BASE/$(echo "$SCALA_CLI_URL" | sed 's@://@/@')"
case "$CACHE_DEST" in
  *.zip)
    sdk_dir=$(basename "$CACHE_DEST" .zip)
    SCALA_CLI_BIN_PATH="${CACHE_DEST%.zip}/$sdk_dir/bin/scala-cli"
    ;;
  *.gz)
    SCALA_CLI_BIN_PATH="${CACHE_DEST%.gz}"
    ;;
  *)
    echoerr "unsupported scala-cli archive: $CACHE_DEST"
    exit 2
    ;;
esac

if [ ! -f "$CACHE_DEST" ]; then
  mkdir -p "$(dirname "$CACHE_DEST")"
  TMP_DEST="$CACHE_DEST.tmp-setup"
  echo "Downloading $SCALA_CLI_URL"
  if command -v curl >/dev/null 2>&1; then
    curl -fLo "$TMP_DEST" "$SCALA_CLI_URL"
  elif command -v wget >/dev/null 2>&1; then
    wget -q -O "$TMP_DEST" "$SCALA_CLI_URL"
  else
    echo "curl or wget is required to download scala-cli" >&2
    exit 1
  fi
  mv "$TMP_DEST" "$CACHE_DEST"
fi

if [ ! -f "$SCALA_CLI_BIN_PATH" ]; then
  case "$CACHE_DEST" in
    *.zip)
      mkdir -p "${CACHE_DEST%.zip}"
      unzip -qo "$CACHE_DEST" -d "${CACHE_DEST%.zip}"
      ;;
    *.gz)
      gzip -dc "$CACHE_DEST" > "$SCALA_CLI_BIN_PATH"
      ;;
  esac
fi

if [ ! -x "$SCALA_CLI_BIN_PATH" ]; then
  chmod +x "$SCALA_CLI_BIN_PATH"
fi

exec "$SCALA_CLI_BIN_PATH" "$@"
