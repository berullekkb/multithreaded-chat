#!/usr/bin/env sh
# Linux / macOS.  ./lancer.sh            -> serveur (TCP 5000 + web http://localhost:8080)
#                 ./lancer.sh client [hôte] [port]
set -e
cd "$(dirname "$0")"
javac -encoding UTF-8 -d bin $(find src -name '*.java')
if [ "$1" = "client" ]; then
  shift
  exec java -cp bin chat.client.Client "$@"
fi
exec java -cp bin chat.server.Serveur "$@"
