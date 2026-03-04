#!/bin/bash
# Lance le Podcatcher sur un serveur local
cd "$(dirname "$0")"
echo "🎙️  Démarrage du Podcatcher..."
echo "👉  Ouvre: http://localhost:8765"
echo "   (Ctrl+C pour arrêter)"
python3 -m http.server 8765 --bind 127.0.0.1
