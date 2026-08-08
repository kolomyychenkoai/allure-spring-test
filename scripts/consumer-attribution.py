#!/usr/bin/env python3
"""Проверка АТРИБУЦИИ шагов: шаг с меткой теста обязан лежать в кейсе ИМЕННО этого теста.

Что доказывает. README обещает, что под forked-JVM и под @Execution(CONCURRENT)
(для перечисленных технологий) шаги не уезжают в соседний тест-кейс. A/B-дифф это НЕ
проверяет — он видит только «тесты не упали». Здесь проверяется само обещание.

Как. Каждый тест витрины метит свои шаги строкой attr-<n>. Собираем «маркер → множество
тест-кейсов, в чьих шагах он встретился». Маркер в двух кейсах = шаг уехал.

Использование: consumer-attribution.py <каталог-сервиса> [ожидаемое-число-маркеров] [префикс]

Ожидаемое число обязательно к указанию в гейте: без него чекер, пропустивший целый канал
из-за узкой регулярки, отрапортует «✅» на оставшихся. Проверено на себе.
"""
import sys
import json
import pathlib
import re
from collections import defaultdict


def step_names(steps):
    """Плоский список имён шагов, включая вложенные."""
    for step in steps:
        yield step.get("name", "")
        yield from step_names(step.get("steps", []))


def main(root: pathlib.Path, expected: int, prefix: str) -> int:
    results = root / "target" / "allure-results"
    if not results.is_dir():
        print(f"✗ нет каталога результатов: {results}")
        return 1

    # [\w-]*\d+, а не \d+: маркеры бывают составными (attr-rest-1). Узкая регулярка
    # молча пропускала бы целый канал, а чекер при этом рапортовал бы «✅».
    marker = re.compile(re.escape(prefix) + r"[\w-]*\d+")
    owners = defaultdict(set)
    cases = 0

    for path in sorted(results.glob("*-result.json")):
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError):
            continue
        case = data.get("fullName") or data.get("name") or path.name
        cases += 1
        for name in step_names(data.get("steps", [])):
            for found in marker.findall(name):
                owners[found].add(case)

    if not owners:
        # Пустой результат читался бы как «нарушений нет» — а это может значить «сбор сломался»
        # либо «шаги вообще не пишутся». Оба случая обязаны быть красными.
        print(f"✗ ни одного маркера {prefix}N в шагах ({cases} кейсов) — сбор сломан либо шагов нет")
        return 1

    leaked = {m: c for m, c in owners.items() if len(c) > 1}
    for m in sorted(owners):
        mark = "❌" if m in leaked else "✅"
        print(f"  {mark} {m}: кейсов {len(owners[m])}")
        if m in leaked:
            for case in sorted(owners[m]):
                print(f"       └ {case}")

    if leaked:
        print(f"\n❌ АТРИБУЦИЯ НАРУШЕНА: {len(leaked)} маркер(ов) в чужих кейсах — шаги уехали")
        return 1
    if expected and len(owners) != expected:
        print(f"\n❌ маркеров {len(owners)}, а ждали {expected} — канал потерян, «✅» было бы ложью")
        return 1
    print(f"\n✅ атрибуция цела: {len(owners)} маркеров, каждый ровно в своём кейсе ({cases} кейсов)")
    return 0


if __name__ == "__main__":
    if len(sys.argv) < 2:
        # Без каталога — печатаем написанную выше строку использования, а не голый IndexError.
        # Дефолт у next() обязателен: иначе переименованная строка докстринга даст StopIteration
        # вместо подсказки, то есть подсказка сломается ровно там, где она и нужна.
        sys.exit(next((l for l in __doc__.splitlines() if l.startswith("Использование:")),
                      f"Использование: {pathlib.Path(sys.argv[0]).name} <каталог-сервиса>"))
    sys.exit(main(pathlib.Path(sys.argv[1]),
                  int(sys.argv[2]) if len(sys.argv) > 2 else 0,
                  sys.argv[3] if len(sys.argv) > 3 else "attr-"))
