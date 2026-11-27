"""Иконки статей из theSVG UI (outline, MIT) → android/.../ui/CategoryIcons.kt.

Скачивает SVG выбранных иконок с jsDelivr, переводит фигуры в пути (path data) и пишет
Kotlin-файл: слаг → пути. Маски theSVG (декоративные разрывы линий) отбрасываются — в
векторной графике Android их нет. Только stdlib:

    python -I tools/ui_icons.py
"""
import sys
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

VERSION = "4.4.0"
URL = "https://cdn.jsdelivr.net/npm/@thesvg/ui@{v}/dist/svg/{slug}/outline.svg"
OUT = Path(__file__).resolve().parent.parent / "android/app/src/main/java/app/cashbook/ui/CategoryIcons.kt"
NS = "{http://www.w3.org/2000/svg}"

# Слаг и название для TalkBack. Порядок — порядок в выборе: еда и покупки, транспорт, дом,
# здоровье, досуг, люди, деньги.
ICONS = [
    ("shopping-cart", "Тележка"), ("shopping-basket", "Корзина"), ("shopping-bag", "Пакет"), ("store", "Магазин"),
    ("coffee", "Кофе"), ("cookie", "Печенье"), ("concierge-bell", "Ресторан"),
    ("bus", "Автобус"), ("car", "Машина"), ("car-taxi-front", "Такси"), ("tram-front", "Трамвай"),
    ("bike", "Велосипед"), ("plane-takeoff", "Самолёт"), ("fuel-gauge", "Топливо"), ("truck", "Грузовик"),
    ("ship", "Корабль"),
    ("house", "Дом"), ("key", "Ключ"), ("zap", "Электричество"), ("droplets", "Вода"), ("flame", "Газ"),
    ("wifi-high", "Интернет"), ("phone", "Телефон"), ("smartphone", "Смартфон"), ("wrench", "Ремонт"),
    ("broom", "Уборка"), ("sprout", "Сад"),
    ("heart", "Сердце"), ("stethoscope", "Врач"), ("tablets", "Лекарства"), ("scissors", "Стрижка"),
    ("graduation-cap", "Учёба"), ("book-open", "Книги"), ("film", "Кино"), ("headphones", "Музыка"),
    ("palette", "Хобби"), ("ferris-wheel", "Развлечения"), ("camera", "Фото"), ("monitor", "Техника"),
    ("trophy", "Спорт"), ("gift", "Подарок"), ("umbrella", "Страховка"), ("globe", "Путешествия"),
    ("package", "Посылка"),
    ("users", "Семья"), ("user", "Человек"), ("handshake", "Помощь"),
    ("briefcase", "Работа"), ("coins", "Монеты"), ("hand-coins", "Выплата"), ("piggy-bank", "Копилка"),
    ("wallet-balance", "Кошелёк"), ("credit-card", "Карта"), ("receipt", "Чек"), ("percent", "Проценты"),
    ("trending-up", "Рост"), ("dollar-sign", "Доллар"), ("award", "Премия"), ("gavel", "Налоги"),
    ("subscription", "Подписки"), ("tag", "Скидка"), ("star", "Звезда"),
]

LICENSE = """MIT License

Copyright (c) GLINCKER / theSVG contributors (https://github.com/glincker/thesvg)

Permission is hereby granted, free of charge, to any person obtaining a copy of this software
and associated documentation files (the "Software"), to deal in the Software without
restriction, including without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the
Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or
substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING
BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE."""


def num(el, name):
    return float(el.get(name, "0"))


def to_path(el):
    """Фигура SVG → path data; неизвестная фигура или transform — ошибка, а не тихая потеря."""
    tag = el.tag.removeprefix(NS)
    if el.get("transform"):
        raise ValueError(f"transform у <{tag}> не поддержан")
    if tag == "path":
        return el.get("d")
    if tag == "circle" or tag == "ellipse":
        cx, cy = num(el, "cx"), num(el, "cy")
        rx = num(el, "r") if tag == "circle" else num(el, "rx")
        ry = num(el, "r") if tag == "circle" else num(el, "ry")
        return f"M{cx - rx} {cy}A{rx} {ry} 0 1 0 {cx + rx} {cy}A{rx} {ry} 0 1 0 {cx - rx} {cy}Z"
    if tag == "line":
        return f"M{num(el, 'x1')} {num(el, 'y1')}L{num(el, 'x2')} {num(el, 'y2')}"
    if tag in ("polyline", "polygon"):
        pts = el.get("points").replace(",", " ").split()
        d = "M" + " L".join(f"{pts[i]} {pts[i + 1]}" for i in range(0, len(pts), 2))
        return d + ("Z" if tag == "polygon" else "")
    if tag == "rect":
        if el.get("rx") or el.get("ry"):
            raise ValueError("rect со скруглением не поддержан")
        x, y, w, h = num(el, "x"), num(el, "y"), num(el, "width"), num(el, "height")
        return f"M{x} {y}H{x + w}V{y + h}H{x}Z"
    raise ValueError(f"фигура <{tag}> не поддержана")


def paths(svg: str):
    root = ET.fromstring(svg)
    out = []
    for g in root.iter(NS + "g"):
        if g.get("stroke") != "currentColor" or g.get("fill") != "none":
            raise ValueError("ожидалась обводка currentColor без заливки")
        if g.get("stroke-width") != "1.75":
            raise ValueError(f"толщина линии {g.get('stroke-width')}, ожидалась 1.75")
        out += [to_path(el) for el in g if el.tag != NS + "g"]
    if not out:
        raise ValueError("пустая иконка")
    if any(set(d) & set('"\\$') for d in out):  # в строке Kotlin дали бы битый литерал или шаблон
        raise ValueError("недопустимый символ в path data")
    return out


def main():
    icons = {}
    for slug, _ in ICONS:
        with urllib.request.urlopen(URL.format(v=VERSION, slug=slug), timeout=30) as r:
            icons[slug] = paths(r.read().decode("utf-8"))
    lines = [
        "package app.cashbook.ui",
        "",
        "// Сгенерировано tools/ui_icons.py — руками не править.",
        f"// Иконки: theSVG UI {VERSION}, вид outline (https://thesvg.org/ui), сетка 24×24, линия 1,75.",
        "// Лицензия иконок:",
        *("// " + l if l else "//" for l in LICENSE.splitlines()),
        "",
        "/** Иконки статей: слаг → пути (path data) обводки; порядок — порядок в выборе. */",
        "val CATEGORY_ICON_PATHS: Map<String, List<String>> = linkedMapOf(",
    ]
    for slug, ds in icons.items():
        joined = ", ".join('"' + d + '"' for d in ds)
        lines.append(f'    "{slug}" to listOf({joined}),')
    lines.append(")")
    lines += ["", "/** Названия иконок для TalkBack. */", "val CATEGORY_ICON_NAMES: Map<String, String> = mapOf("]
    lines += [f'    "{slug}" to "{name}",' for slug, name in ICONS]
    lines.append(")")
    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    print(f"{len(icons)} icons -> {OUT}")  # ASCII: консоль Windows в cp1251


if __name__ == "__main__":
    sys.exit(main())
