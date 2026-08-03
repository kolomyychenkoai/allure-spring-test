#!/usr/bin/env bash
#
# Матрица совместимости: гоняет сборку на границах объявленной поддержки.
#
# Это НЕ часть ежедневного цикла — ежедневный цикл — это `mvn clean test`.
# Матрица гоняется перед релизом и при любой правке compat.* в pom.
#
# Не падает на первой точке: нужна ВСЯ сводка, иначе после каждой починки
# пришлось бы гонять заново. Итоговый код возврата ненулевой, если упало хоть что-то.
#
# Первый прогон compat-boot-min долгий (3–10 мин): качается весь BOM Boot 3.2
# (Spring 6.1, Hibernate 6.4, Kafka 3.6, Liquibase 4.2x). Дальше — из ~/.m2.
#
set -u
cd "$(dirname "$0")/.." || exit 2

# Логи НЕ в target/: каждая следующая точка делает `mvn clean` и стёрла бы предыдущие.
LOGS=.compat-logs
mkdir -p "$LOGS"
fail=0

run() {
    local id=$1
    shift
    local started status
    started=$(date +%s)
    if mvn -B clean test "$@" > "$LOGS/$id.log" 2>&1; then
        status="OK  "
    else
        status="FAIL"
        fail=1
    fi
    printf '%s  %-12s %4ss  → %s/%s.log\n' \
        "$status" "$id" "$(( $(date +%s) - started ))" "$LOGS" "$id"

    # На неродной версии сверка с эталоном выключена, и расхождения печатаются
    # человеку. Показываем их объём здесь же: пропажа десятков видов сразу видна.
    if grep -q 'СВЕРКА С ЭТАЛОНОМ ВЫКЛЮЧЕНА' "$LOGS/$id.log"; then
        printf '      видов разошлось: %s (подробности в логе)\n' \
            "$(grep -c '✗ шаг\|✗ вложение' "$LOGS/$id.log")"
    fi
}

echo "Матрица совместимости — $(date '+%Y-%m-%d %H:%M')"
echo

run native
run java25      -Pjava25
run allure-min  -Pcompat-allure-min
run allure-max  -Pcompat-allure-max
run boot-min    -Pcompat-boot-min

echo
if [ $fail -eq 0 ]; then
    echo "Все точки прошли. Границы в pom (compat.*) и таблица в README подтверждены."
else
    echo "Есть падения. Правило: границу ПОДНИМАЕМ, а не подкручиваем тесты под неё."
fi
exit $fail
