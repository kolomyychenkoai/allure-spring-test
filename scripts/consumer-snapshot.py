#!/usr/bin/env python3
"""Снимок прогона сервиса-потребителя: исходы тестов + наблюдаемое поведение.

Исходы берутся по элементам <testcase>, а НЕ по атрибуту tests= в корне: у класса, где
все тесты в @Nested, там стоит 0 — на этом уже терялись тесты при апгрейде.

Использование: snapshot.py <каталог-сервиса> > snapshot.txt
"""
import sys
import pathlib
import xml.etree.ElementTree as ET


def outcome(case):
    for tag, name in (("failure", "FAILED"), ("error", "ERROR"), ("skipped", "SKIPPED")):
        if case.find(tag) is not None:
            return name
    return "PASSED"


def main(root: pathlib.Path) -> None:
    lines = []

    reports = root / "target" / "surefire-reports"
    for xml in sorted(reports.glob("TEST-*.xml")) if reports.is_dir() else []:
        try:
            tree = ET.parse(xml)
        except ET.ParseError as broken:
            lines.append(f"TEST | <неразобранный отчёт {xml.name}: {broken}>")
            continue
        for case in tree.iter("testcase"):
            cls = case.get("classname", "?")
            name = case.get("name", "?")
            lines.append(f"TEST | {cls}#{name} → {outcome(case)}")

    behavior = root / "target" / "behavior.log"
    if behavior.is_file():
        lines.extend(behavior.read_text(encoding="utf-8").splitlines())
    else:
        lines.append("BEHAVIOR | <файла нет: рекордер не отработал>")

    # Сортируем: порядок тест-классов у потребителя случайный (runOrder), и несортированный
    # снимок расходился бы сам по себе, без всякой библиотеки.
    print("\n".join(sorted(line.rstrip() for line in lines if line.strip())))


if __name__ == "__main__":
    main(pathlib.Path(sys.argv[1]))
