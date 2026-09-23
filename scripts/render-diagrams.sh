#!/usr/bin/env bash
# Render every Mermaid diagram in the docs to PNG, for slides and written reports.
#
# The markdown is the single source of truth: this script extracts the ```mermaid blocks
# rather than keeping a separate copy, so the PNG cannot drift from the diagram GitHub
# renders. Re-run it after editing any diagram.
#
#   ./scripts/render-diagrams.sh
#
# Name a diagram by putting "%% name: <something>" as the first line of the block;
# it is written to docs/images/<something>.png. Unnamed blocks fall back to <file>-<n>.
set -euo pipefail

cd "$(dirname "$0")/.."
OUT_DIR="docs/images"
SCALE="${SCALE:-3}"        # 3x for print; override with SCALE=1 for a quick check
WIDTH="${WIDTH:-1600}"
mkdir -p "$OUT_DIR"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

python3 - "$TMP" README.md docs/*.md <<'PYEOF'
import pathlib, re, sys

tmp = pathlib.Path(sys.argv[1])
manifest = []
for src in sys.argv[2:]:
    path = pathlib.Path(src)
    if not path.is_file():
        continue
    text = path.read_text(encoding="utf-8")
    for index, block in enumerate(re.findall(r"```mermaid\n([\s\S]*?)```", text), start=1):
        named = re.match(r"\s*%%\s*name:\s*([A-Za-z0-9_-]+)", block)
        name = named.group(1) if named else f"{path.stem.lower()}-{index}"
        (tmp / f"{name}.mmd").write_text(block, encoding="utf-8")
        manifest.append(f"{name}\t{src}")

(tmp / "manifest.txt").write_text("".join(line + "\n" for line in manifest), encoding="utf-8")
print(f"found {len(manifest)} diagram(s)")
PYEOF

while IFS=$'\t' read -r name src; do
  [ -z "$name" ] && continue
  echo "  rendering $name  (from $src)"
  npx -y @mermaid-js/mermaid-cli \
      -i "$TMP/$name.mmd" \
      -o "$OUT_DIR/$name.png" \
      -b white -w "$WIDTH" -s "$SCALE" 2>&1 | grep -vE "^$" | tail -3
done < "$TMP/manifest.txt"

echo
ls -la "$OUT_DIR"/*.png 2>/dev/null | awk '{printf "  %-44s %8.1f KB\n", $NF, $5/1024}'
