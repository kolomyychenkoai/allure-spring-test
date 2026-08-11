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

. "$(dirname "$0")/_tools.sh"

if [ "$SHOW_ALL" = "1" ]; then
    tools report-tree "$RESULTS" --all
else
    tools report-tree "$RESULTS"
fi

if [ "$OPEN_HTML" = "1" ]; then
    echo
    echo "Собираю HTML…"
    mvn -q allure:report && open target/site/allure-maven-plugin/index.html
fi
