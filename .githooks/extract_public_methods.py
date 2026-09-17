#!/usr/bin/env python3
"""Достаёт имена публичных методов из Java-файла простым регэкспом (без AST) —
достаточно для типичного сервиса/контроллера, не рассчитано на экзотические
многострочные сигнатуры с дженериками."""
import re
import sys


def main() -> None:
    path = sys.argv[1]
    with open(path, encoding="utf-8") as f:
        content = f.read()

    class_match = re.search(r"\b(?:class|interface|record)\s+(\w+)", content)
    class_name = class_match.group(1) if class_match else None

    # [public] [static] ТипВозврата имяМетода(...) {
    pattern = re.compile(
        r"\bpublic\s+(?:static\s+)?(?:[\w<>\[\],.?\s]+?)\s+(\w+)\s*\([^;{]*\)\s*\{"
    )

    seen = []
    for match in pattern.finditer(content):
        name = match.group(1)
        if name == class_name:  # конструктор — не метод
            continue
        if name not in seen:
            seen.append(name)

    print("\n".join(seen))


if __name__ == "__main__":
    main()
