#!/usr/bin/env bash
#
# Сколько времени добавляет подключение библиотеки к сьюту потребителя.
#
# По N прогонов на каждую сторону, берём МЕДИАНУ и печатаем РАЗБРОС (min–max). Разброс
# обязателен: на одной машине соседние прогоны гуляют, и дельта меньше разброса — это шум,
# а не замер. Подавать такую дельту как результат нельзя.
#
# Время берём в МИЛЛИСЕКУНДАХ через python3: дельта тут порядка секунды, и посекундный
# `date +%s` мерил бы на грани собственного разрешения. macOS `date` не умеет %3N,
# а bash 3.2 не знает EPOCHREALTIME.
#
# ⚠️ Без ассоциативных массивов и отрицательных индексов: на macOS это bash 3.2
# (та же оговорка, что в scripts/compat-matrix.sh библиотеки).
#
# Использование:  ./consumer-overhead.sh [прогонов]   (по умолчанию 5)

set -u
LIB=$(cd "$(dirname "$0")/.." && pwd)
HERE=${CONSUMERS_DIR:-~/projects/allure-consumers}
HERE=${HERE/#\~/$HOME}
[[ -d $HERE ]] || { echo "✗ нет каталога сервисов: $HERE — рецепт в docs/consumer-affects.md"; exit 1; }
cd "$HERE" || exit 1
RUNS=${1:-5}
# Аргумент проверяем сразу: при 0 или нечисле цикл замера не сделает ни витка, а дальше
# ${sorted[0]} под set -u уронил бы скрипт стеком вместо внятного «мерить нечего».
case $RUNS in
    ''|*[!0-9]*) echo "✗ прогонов должно быть целым числом, а не «$RUNS»"; exit 1 ;;
esac
[[ $RUNS -ge 1 ]] || { echo "✗ прогонов должно быть хотя бы 1 (медиана и разброс иначе не считаются)"; exit 1; }

echo "▸ ставим библиотеку в ~/.m2"
(cd "$LIB" && mvn -q clean install -DskipTests) || { echo "✗ библиотека не собралась"; exit 1; }

# Миллисекунды → «12.345s» для чтения человеком.
ms() { printf '%d.%03ds' $(($1 / 1000)) $(($1 % 1000)); }

# Медиана и разброс одной стороны: печатает строку и возвращает медиану через MEDIAN.
measure() {
    local svc=$1 phase=$2
    local args=(clean test)
    [[ $phase == with ]] && args+=(-Pallure-lib)

    local times=() start i
    for ((i = 0; i < RUNS; i++)); do
        start=$(python3 -c 'import time;print(int(time.time()*1000))')
        (cd "$svc" && mvn -q "${args[@]}" > /dev/null 2>&1)
        times+=($(($(python3 -c 'import time;print(int(time.time()*1000))') - start)))
    done

    local sorted
    sorted=($(printf '%s\n' "${times[@]}" | sort -n))
    MEDIAN=${sorted[$((RUNS / 2))]}
    printf '%-22s %-10s %8s %8s %8s\n' "$svc" "$phase" "$(ms "$MEDIAN")" "$(ms "${sorted[0]}")" "$(ms "${sorted[$((RUNS - 1))]}")"
}

printf '\n%-22s %-10s %8s %8s %8s\n' "сервис" "сторона" "медиана" "min" "max"
printf '%.0s─' {1..62}; echo

for svc in svc-*/; do
    svc=${svc%/}
    measure "$svc" without; base=$MEDIAN
    measure "$svc" with;    lib=$MEDIAN
    if [[ $base -gt 0 ]]; then
        printf '%-22s %-10s %7s%%   абсолют +%sмс   (сверяй с разбросом выше)\n\n' \
            "$svc" "ДЕЛЬТА" "$(((lib - base) * 100 / base))" "$((lib - base))"
    else
        printf '%-22s %-10s %s\n\n' "$svc" "ДЕЛЬТА" "база 0 с — мерить нечего"
    fi
done
