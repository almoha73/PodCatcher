#!/bin/bash
# Lance le Podcatcher sur un serveur local
cd "$(dirname "$0")"
echo "🎙️  Démarrage du Podcatcher..."
echo "👉  Ouvre: http://localhost:8765"
echo "   (Ctrl+C pour arrêter)"
python3 serveur.py --port 8765 --dir .
