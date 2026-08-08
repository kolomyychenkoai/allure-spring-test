#!/usr/bin/env bash
#
# A/B-проверка аффектов библиотеки на чужой сервис (issues #43 и #46).
#
# Один и тот же тест-код гоняется ДВАЖДЫ: без allure-spring-test и с ней (профиль
# allure-lib). Различается ровно одна зависимость в pom — правок в тестах нет ни одной.
# Снимок «исходы тестов + наблюдаемое поведение» обязан совпасть байт-в-байт.
#
# Зачем именно так. Апгрейд на Boot 4 показал, что библиотека способна сломать тест
# потребителя одним фактом подключения (перехват читал одноразовое тело ответа), а витрина
# самой библиотеки такого не видит: она устроена ПОД неё.
#
# Прогон НЕ прерывается на красном: красный прогон — это тоже снимок, и сравнить его
# с базовым важнее, чем упасть.
#
# Режим запуска задаётся переменной MODE (по умолчанию plain):
#   plain      — обычный прогон
#   forked     — forkCount=2, reuseForks=false. README называет этот режим «полностью ОК»
#   concurrent — @Execution(CONCURRENT) в одной JVM
#
# ⚠️ concurrent осмыслен ТОЛЬКО на svc-a: MVC/JPA/ассерты — это список «ОК» из README.
# В svc-b/svc-c живут статический WireMock-сервер и встроенный брокер, они нестабильны под
# потоковой параллелью САМИ ПО СЕБЕ — поплыли бы обе стороны A/B, и сравнение потеряло бы смысл.
#
# Использование:  ./consumer-matrix.sh [каталог-сервиса ...]   (по умолчанию — все svc-*)

set -u
LIB=$(cd "$(dirname "$0")/.." && pwd)
HERE=${CONSUMERS_DIR:-~/projects/allure-consumers}
HERE=${HERE/#\~/$HOME}
[[ -d $HERE ]] || { echo "✗ нет каталога сервисов: $HERE — рецепт в docs/consumer-affects.md"; exit 1; }
cd "$HERE" || exit 1

MODE=${MODE:-plain}
case $MODE in
    plain)  MODE_ARGS=() ;;
    forked) MODE_ARGS=(-DforkCount=2 -DreuseForks=false) ;;
    concurrent) MODE_ARGS=(
        -Djunit.jupiter.execution.parallel.enabled=true
        -Djunit.jupiter.execution.parallel.mode.default=concurrent
        -Djunit.jupiter.execution.parallel.mode.classes.default=concurrent) ;;
    *) echo "✗ неизвестный MODE=$MODE (plain|forked|concurrent)"; exit 1 ;;
esac

services=("$@")
if [[ ${#services[@]} -eq 0 ]]; then
    services=(svc-*/)
fi

echo "▸ ставим библиотеку в ~/.m2 (она не опубликована — потребитель берёт SNAPSHOT локально)"
if ! (cd "$LIB" && mvn -q clean install -DskipTests); then
    echo "✗ не собралась сама библиотека — сравнивать нечего"
    exit 1
fi

fail=0
for svc in "${services[@]}"; do
    svc=${svc%/}
    [[ -d $svc ]] || { echo "✗ нет каталога $svc"; fail=1; continue; }
    echo
    echo "════════ $svc  (режим: $MODE)"

    for phase in without with; do
        args=(clean test "${MODE_ARGS[@]+"${MODE_ARGS[@]}"}")
        [[ $phase == with ]] && args+=(-Pallure-lib)
        echo "  ▸ прогон $phase: mvn ${args[*]}"
        (cd "$svc" && mvn -q "${args[@]}" > "$HERE/$svc-$MODE-$phase.log" 2>&1)
        python3 "$LIB/scripts/consumer-snapshot.py" "$svc" > "$HERE/$svc-$MODE-$phase.snapshot"
    done

    if diff -u "$svc-$MODE-without.snapshot" "$svc-$MODE-with.snapshot" > "$svc-$MODE.diff"; then
        n=$(grep -c '^TEST | ' "$svc-$MODE-without.snapshot")
        echo "  ✅ снимки идентичны ($n тестов) — библиотека ничего не изменила"
        rm -f "$svc-$MODE.diff"
    else
        echo "  ❌ РАСХОЖДЕНИЕ — разбирать: $svc-$MODE.diff"
        sed 's/^/     /' "$svc-$MODE.diff" | head -40
        fail=1
    fi
done

echo
if [[ $fail -eq 0 ]]; then
    echo "✅ все сервисы: подключение библиотеки не изменило ни исходов тестов, ни поведения"
else
    echo "❌ есть расхождения либо сбои — см. вывод выше и *.diff"
fi
exit $fail
