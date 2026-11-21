"""Выгрузка дерева статей AbilityCash (.cash) в ассет приложения.

Базу открывает только для чтения. Корни классификатора («Все статьи прихода/расхода»)
не переносятся — их дети становятся верхним уровнем. Удалённые статьи пропускаются вместе
с потомками. Порядок — по Id, как в .cash.

    python tools/export_categories.py main_cash_test.cash android/app/src/main/assets/categories.json
"""
import json
import sqlite3
import sys
from pathlib import Path


def load(cash_path):
    path = Path(cash_path).resolve()
    if not path.is_file():
        raise SystemExit(f"Нет файла: {path}")
    # as_uri() кодирует '#', '?', '%' и пробелы — иначе URI обрывается и mode=ro теряется.
    db = sqlite3.connect(path.as_uri() + "?mode=ro", uri=True)
    try:
        roots = db.execute(
            "SELECT IncomeTreeRoot, ExpenseTreeRoot FROM Classifiers WHERE Deleted = 0 ORDER BY Id LIMIT 1"
        ).fetchone()
        if roots is None:
            raise SystemExit("В .cash нет классификатора статей")
        rows = db.execute("SELECT Id, Name, Parent FROM Categories WHERE Deleted = 0 ORDER BY Id").fetchall()
    finally:
        db.close()

    children = {}
    for cid, name, parent in rows:
        children.setdefault(parent, []).append((cid, name))

    def subtree(cid):
        return [{"name": name, "children": subtree(k)} for k, name in children.get(cid, [])]

    return {"income": subtree(roots[0]), "expense": subtree(roots[1])}


def count(nodes):
    return sum(1 + count(n["children"]) for n in nodes)


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    tree = load(sys.argv[1])
    with open(sys.argv[2], "w", encoding="utf-8", newline="\n") as f:
        json.dump(tree, f, ensure_ascii=False, indent=1)
        f.write("\n")
    print(f"income: {count(tree['income'])}, expense: {count(tree['expense'])} -> {sys.argv[2]}")


if __name__ == "__main__":
    main()
