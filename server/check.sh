#!/bin/sh
set -eu
cd "$(dirname "$0")"
go test -race ./...
go vet ./...
python3 - <<'PY'
import ast,pathlib
for path in pathlib.Path('packaging').glob('*.py'):ast.parse(path.read_text())
print('Packaging syntax verified')
PY
sh -n packaging/install.sh packaging/mjwatchctl
