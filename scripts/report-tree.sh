#!/usr/bin/env bash
#
# Ось «отчёт» из docs/review-playbook.md: пройти отчёт ГЛАЗАМИ так, как его читает
# ручной тестировщик, — по дереву шагов, не открывая код тестов.
#
# Зачем отдельный инструмент. Ось «отчёт» до сих пор закрывалась чтением JSON-результатов
# и тел вложений — то есть проверкой, что данные на месте. Но критерий приёмки
# (docs/acceptance-report-standard.md) другой: по отчёту должно быть ПОНЯТНО, что
# проверялось и почему результат верный. Это видно только на дереве целиком, а собирать
# его руками дорого — без готового инструмента проход просто пропускают.
#
# Что печатает:
#   1. сводку (тестов, шагов, вложений; витрина отдельно от внутренних проверок);
#   2. дерево витринных тестов: класс → тест → шаги с вложенностью и вложениями;
#   3. подсказки «сюда смотреть»: серии одинаковых шагов подряд (шум, топящий смысл).
#
# Пункт 3 — не гейт, а лупа: решает человек. Гейта тут быть и не может — «читаемо»
# машиной не проверяется, за этим и нужен глаз.
#
# Использование:
#   scripts/report-tree.sh            # дерево витрины
#   scripts/report-tree.sh --all      # + внутренние проверки библиотеки
#   scripts/report-tree.sh --open     # собрать HTML и открыть в браузере
#
# Требует уже выполненного `mvn clean test` (читает target/allure-results).
#
set -u
cd "$(dirname "$0")/.." || exit 2

RESULTS=target/allure-results
SHOW_ALL=0
OPEN_HTML=0
for arg in "$@"; do
    case "$arg" in
        --all) SHOW_ALL=1 ;;
        --open) OPEN_HTML=1 ;;
        *) echo "неизвестный аргумент: $arg (см. шапку скрипта)" >&2; exit 2 ;;
    esac
done

if [ ! -d "$RESULTS" ] || [ -z "$(ls -A "$RESULTS" 2>/dev/null)" ]; then
    echo "Нет $RESULTS — сначала полный прогон: mvn clean test" >&2
    echo "(точечный -Dtest=… не годится: он выключает профиль report-inventory)" >&2
    exit 2
fi

command -v python3 >/dev/null 2>&1 || { echo "нужен python3" >&2; exit 2; }

SHOW_ALL="$SHOW_ALL" python3 - "$RESULTS" <<'PY'
import json, os, sys, glob, collections, itertools

results, show_all = sys.argv[1], os.environ.get("SHOW_ALL") == "1"
INTERNAL = "Внутренние проверки библиотеки"
RUN = 5  # серия одинаковых шагов подряд, с которой начинается шум

tests = []
for path in sorted(glob.glob(os.path.join(results, "*-result.json"))):
    with open(path, encoding="utf-8") as fh:
        d = json.load(fh)
    labels = {l["name"]: l["value"] for l in d.get("labels", [])}
    tests.append((labels.get("epic") or "—", labels.get("testClass", "?").split(".")[-1], d))

steps = attachments = 0
def walk(node, depth, out):
    global steps, attachments
    for s in node.get("steps", []):
        steps += 1
        names = [a.get("name", "?") for a in s.get("attachments", [])]
        attachments += len(names)
        status = "" if s.get("status") == "passed" else "  [%s]" % (s.get("status") or "?").upper()
        tail = "   {%s}" % ", ".join(names) if names else ""
        out.append(("    " * depth + "• " + s.get("name", ""), status + tail, s.get("name", "")))
        walk(s, depth + 1, out)

by_epic = collections.Counter(e for e, _, _ in tests)
print("=" * 100)
print("ОТЧЁТ: %d тестов" % len(tests))
for epic, n in by_epic.most_common():
    print("   %5d  %s%s" % (n, epic, "   ← витрина, её и читает тестировщик" if epic != INTERNAL else ""))

noisy = []
shown = [t for t in tests if show_all or t[0] != INTERNAL]
for cls, group in itertools.groupby(sorted(shown, key=lambda t: (t[1], t[2].get("name", ""))), key=lambda t: t[1]):
    print("\n" + "=" * 100)
    print(cls)
    for _, _, d in group:
        files = [a.get("name", "?") for a in d.get("attachments", [])]
        print("  ТЕСТ: %s%s" % (d.get("name", ""), "   {%s}" % ", ".join(files) if files else ""))
        out = []
        walk(d, 2, out)
        for line, tail, _ in out:
            print(line + tail)
        # серии одинаковых имён подряд — то, что топит смысловые шаги в служебных
        prev, run = None, 0
        for _, _, name in out:
            if name == prev:
                run += 1
                if run + 1 == RUN:
                    noisy.append((cls, d.get("name", "")[:50], name))
            else:
                prev, run = name, 0

print("\n" + "=" * 100)
print("ИТОГО: шагов %d, вложений %d" % (steps, attachments))
if noisy:
    print("\nСЮДА СМОТРЕТЬ — серии одинаковых шагов от %d подряд (смысловой шаг тонет):" % RUN)
    for cls, test, name in noisy:
        print("   %s :: %s → «%s»" % (cls, test, name[:60]))
    print("   Решает человек: это может быть служебная кухня инструмента (тогда вопрос —")
    print("   увидит ли такое потребитель) либо реальный дефект имён.")
else:
    print("\nСерий одинаковых шагов от %d подряд нет." % RUN)
print("""
Что проверять глазами (docs/acceptance-report-standard.md):
  · понятно ли ПО ИМЕНАМ, что проверялось, — не открывая код теста;
  · верна ли вложенность (SQL внутри вызова репозитория, тела внутри HTTP-шага);
  · нет ли технического мусора: Класс@хэш, [B@…, сырой toString;
  · нет ли шагов, чьё имя не отвечает «что именно проверили».""")
PY

if [ "$OPEN_HTML" = "1" ]; then
    echo
    echo "Собираю HTML…"
    mvn -q allure:report && open target/site/allure-maven-plugin/index.html
fi
