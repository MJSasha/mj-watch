#!/bin/sh
set -eu
umask 077
[ "$(id -u)" = 0 ] || exit 1
command -v python3 >/dev/null || { apt-get update; DEBIAN_FRONTEND=noninteractive apt-get install -y python3; }
exec python3 /var/lib/mjwatch-install/upload/install.py
