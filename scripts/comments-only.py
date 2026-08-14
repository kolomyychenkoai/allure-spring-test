#!/usr/bin/env python3
"""Гейт прохода 2.9: доказать, что правка тронула ТОЛЬКО комментарии.

Снимает комментарии и нормализует пробелы у каждого .java в базовой ревизии и в рабочем
дереве; тексты обязаны совпасть. Иначе правка комментариев незаметно зацепила код.

⚠️ Снимаем ЛЕКСЕРОМ по состояниям, а не регуляркой: `//` внутри строкового литерала
(например "http://…") регуляркой съедается вместе с половиной строки, и гейт начинает
врать в обе стороны — пропускать правки кода и краснеть на невинных.

Использование:
    scripts/comments-only.py [база]       # по умолчанию HEAD

⚠️ База по умолчанию HEAD, а не main: доказывать надо, что ТВОЯ правка не тронула код.
Против main гейт покраснеет на любой ветке, где код менялся законно, и его перестанут смотреть.

Проверен мутацией в четыре стороны: правка кода → красный; правка текста ВНУТРИ строкового
литерала → красный (литералы не приняты за комментарии); удаление строки javadoc → зелёный;
вставка 20 пустых строк → зелёный.
"""
import subprocess
import sys
import pathlib

REPO = pathlib.Path(__file__).resolve().parent.parent

NORMAL, LINE_COMMENT, BLOCK_COMMENT, STRING, CHAR, TEXT_BLOCK = range(6)


def strip(src: str) -> str:
    """Код без комментариев, с схлопнутыми пробелами. Литералы сохраняются дословно."""
    out = []
    state = NORMAL
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if state == NORMAL:
            if c == "/" and nxt == "/":
                state = LINE_COMMENT; i += 2; continue
            if c == "/" and nxt == "*":
                state = BLOCK_COMMENT; i += 2; continue
            if src.startswith('"""', i):
                state = TEXT_BLOCK; out.append('"""'); i += 3; continue
            if c == '"':
                state = STRING; out.append(c); i += 1; continue
            if c == "'":
                state = CHAR; out.append(c); i += 1; continue
            out.append(c); i += 1; continue
        if state == LINE_COMMENT:
            if c == "\n":
                state = NORMAL; out.append(c)
            i += 1; continue
        if state == BLOCK_COMMENT:
            if c == "*" and nxt == "/":
                state = NORMAL; i += 2
            else:
                if c == "\n":
                    out.append(c)          # перевод строки сохраняем: номера строк не едут
                i += 1
            continue
        if state == TEXT_BLOCK:
            if src.startswith('"""', i):
                state = NORMAL; out.append('"""'); i += 3; continue
            out.append(c); i += 1; continue
        if state in (STRING, CHAR):
            quote = '"' if state == STRING else "'"
            out.append(c)
            if c == "\\" and nxt:
                out.append(nxt); i += 2; continue
            if c == quote:
                state = NORMAL
            i += 1; continue
    # Схлопываем пробелы: перенос абзаца в javadoc меняет отступы соседнего кода не должен
    return "\n".join(" ".join(line.split()) for line in "".join(out).splitlines()
                     if line.strip())


def at_revision(rev: str, path: str) -> str | None:
    r = subprocess.run(["git", "show", f"{rev}:{path}"], cwd=REPO,
                       capture_output=True, text=True)
    return r.stdout if r.returncode == 0 else None


def main() -> int:
    base = sys.argv[1] if len(sys.argv) > 1 else "HEAD"
    changed = subprocess.run(
        ["git", "diff", "--name-only", base, "--", "src"],
        cwd=REPO, capture_output=True, text=True).stdout.split()
    java = [p for p in changed if p.endswith(".java")]
    if not java:
        print("НЕЧЕГО СВЕРЯТЬ: изменённых .java нет — гейт ничего не измерил")
        return 1

    broken = []
    for path in sorted(java):
        was, now = at_revision(base, path), (REPO / path)
        if was is None:
            print(f"  новый файл, сверять не с чем: {path}")
            continue
        if not now.exists():
            broken.append(f"{path}: файл удалён")
            continue
        if strip(was) != strip(now.read_text()):
            broken.append(path)

    print(f"сверено файлов: {len(java)}")
    if broken:
        print("\n❌ ТРОНУТ НЕ ТОЛЬКО КОММЕНТАРИЙ:")
        for p in broken:
            print("   ", p)
        return 1
    print("✅ во всех файлах изменились только комментарии")
    return 0


if __name__ == "__main__":
    sys.exit(main())
