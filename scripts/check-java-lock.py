#!/usr/bin/env python3
"""Detect dependency-graph drift after a Maven package build."""
from pathlib import Path
root=Path(__file__).resolve().parents[1]
actual=(root/'target/quarkus-app/quarkus-app-dependencies.txt').read_text().splitlines()
expected=(root/'dependency-lock.txt').read_text().splitlines()
if sorted(actual)!=sorted(expected):
    raise SystemExit('Java dependency graph changed. Review the BOM/dependency updates, then regenerate dependency-lock.txt from target/quarkus-app/quarkus-app-dependencies.txt.')
print(f'Java dependency lock matches {len(actual)} artifacts.')
