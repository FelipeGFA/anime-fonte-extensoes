#!/bin/bash
set -e

git config --global user.email "felipegabriel.avila6@gmail.com"
git config --global user.name "FelipeGFA"
git status
if [ -n "$(git status --porcelain)" ]; then
    git add .
    git commit -m "Update extensions repo"
    git push

    # Purge cached index on jsDelivr
    curl https://purge.jsdelivr.net/gh/FelipeGFA/anime-extensoes@repo/index.min.json
else
    echo "No changes to commit"
fi
