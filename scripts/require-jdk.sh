#!/usr/bin/env bash
#
# Проверка, что Maven пойдёт на нужной JDK. Подключается точкой из других скриптов.
#
# Зачем. Библиотека собирается под Java 25 (maven.compiler.release=25). Maven берёт JDK из
# JAVA_HOME, а НЕ из того, что показывает `java -version`: при jenv это разные вещи, и в
# шелле без инициализации jenv (фоновые задачи, IDE-терминал, cron) JAVA_HOME спокойно
# остаётся от прошлой версии. Тогда сборка падает с
#
#     error: release version 25 not supported
#
# — сообщением, по которому причину не найти. До перехода на Java 25 неверный JAVA_HOME был
# безобиден (всё собиралось под 21), поэтому раньше проверки и не требовалось.
#
# Обратный случай — JAVA_HOME НОВЕЕ (26) — здесь НЕ ловим намеренно: компиляция пройдёт,
# а несовпадение поймает канарейка runsOnExpectedJvm по expected.java.feature. Дублировать
# работающий гейт вторым, который легко разъедется с pom, ни к чему.

REQUIRED_JDK=25

_jdk_feature() {
    "$1" -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/'
}

require_jdk() {
    local java_bin feature where
    if [ -n "${JAVA_HOME:-}" ]; then
        java_bin="$JAVA_HOME/bin/java"
        where="JAVA_HOME=$JAVA_HOME"
    else
        java_bin=$(command -v java) || {
            echo "❌ java не найдена в PATH" >&2
            exit 2
        }
        where="java из PATH ($java_bin)"
    fi

    feature=$(_jdk_feature "$java_bin")
    case "$feature" in
        ''|*[!0-9]*)
            echo "⚠️  не удалось определить версию JDK ($where) — продолжаю, решать сборке" >&2
            return 0
            ;;
    esac

    if [ "$feature" -lt "$REQUIRED_JDK" ]; then
        cat >&2 <<EOF
❌ Maven пойдёт на Java $feature, а проект собирается под Java $REQUIRED_JDK.
   Источник: $where

   Именно JAVA_HOME решает, какой javac возьмёт Maven — \`java -version\` может показывать
   другое (у jenv это разные вещи). Починить:

       jenv local $REQUIRED_JDK          # в каталоге проекта, уже зафиксировано в .java-version
       eval "\$(jenv init -)"            # если шелл без инициализации jenv

   Без этого сборка упадёт с «error: release version $REQUIRED_JDK not supported».
EOF
        exit 2
    fi
}
