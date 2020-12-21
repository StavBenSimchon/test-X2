#! /usr/bin/env bash
set -e
git-cp(){
    git add -A && git commit -m "$1" && git push 
}
case "$1" in
    "git-cp")
        git-cp ${@:2}
    ;;
esac