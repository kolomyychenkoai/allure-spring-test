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
# Использование:  ./consumer-matrix.sh [каталог-сервиса ...]   (по умолчанию — все svc-*)

set -u
LIB=$(cd "$(dirname "$0")/.." && pwd)
HERE=${CONSUMERS_DIR:-~/projects/allure-consumers}
HERE=${HERE/#\~/$HOME}

if [[ ! -d $HERE ]]; then
    cat <<MSG
✗ нет каталога сервисов-потребителей: $HERE

  Сервисы живут ВНЕ git (решение по issue #46) — на новой машине их надо создать заново.
  Рецепт: docs/consumer-affects.md, раздел «Как пересобрать сервисы».
  Каталог можно задать переменной CONSUMERS_DIR.
MSG
    exit 1
fi
cd "$HERE" || exit 1

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
    echo "════════ $svc"

    for phase in without with; do
        args=(clean test)
        [[ $phase == with ]] && args+=(-Pallure-lib)
        echo "  ▸ прогон $phase: mvn ${args[*]}"
        (cd "$svc" && mvn -q "${args[@]}" > "$HERE/$svc-$phase.log" 2>&1)
        python3 "$LIB/scripts/consumer-snapshot.py" "$svc" > "$HERE/$svc-$phase.snapshot"
    done

    if diff -u "$svc-without.snapshot" "$svc-with.snapshot" > "$svc.diff"; then
        n=$(grep -c '^TEST | ' "$svc-without.snapshot")
        echo "  ✅ снимки идентичны ($n тестов) — библиотека ничего не изменила"
        rm -f "$svc.diff"
    else
        echo "  ❌ РАСХОЖДЕНИЕ — разбирать: $svc.diff"
        sed 's/^/     /' "$svc.diff" | head -40
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
