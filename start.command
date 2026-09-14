#!/bin/zsh
set -e
cd "${0:A:h}"
if [[ -x 'composeApp/build/compose/binaries/main/app/YMLStudio.app/Contents/MacOS/YMLStudio' ]]; then
  exec 'composeApp/build/compose/binaries/main/app/YMLStudio.app/Contents/MacOS/YMLStudio' "$@"
fi
exec ./gradlew :composeApp:run --console=plain
