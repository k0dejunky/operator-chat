#!/bin/bash
# Push the operator chat app to its GitHub repo.
# Run this AFTER granting the fine-grained PAT write access to k0dejunky/operator-chat
# (GitHub Settings > Developer settings > Fine-grained tokens > gallery-site PAT > add operator-chat).
set -e
cd "$(dirname "$0")"
TOKEN="${1:-$GITHUB_TOKEN}"
if [ -z "$TOKEN" ]; then
  echo "Usage: push-to-github.sh <PAT>   (or set GITHUB_TOKEN)"
  exit 1
fi
git remote set-url origin "https://k0dejunky:${TOKEN}@github.com/k0dejunky/operator-chat.git"
git push -u origin main
git remote set-url origin "https://github.com/k0dejunky/operator-chat.git"
echo "Pushed. The GitHub Actions workflow will build the APK (Actions tab)."
