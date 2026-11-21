"""Перенос AbilityCash (.cash) в копию базы Cashbook — для «Импорт копии базы» на телефоне.

Исходный .cash не меняется: он копируется во временный файл и читается только оттуда (mode=ro).
Пустая база Cashbook собирается по схеме Room (android/app/schemas/.../<версия>.json): те же
таблицы, индексы и identity_hash — иначе приложение копию не примет.

Переносится: валюты (код — первые 3 буквы, «UAH ₴» → UAH), счета (удалённые — в архив),
дерево статей (корни «Все статьи прихода/расхода» не переносятся — их дети верхний уровень),
операции (id, дата, порядок в дне, суммы по модулю, статья, комментарий). Операция на корне
дерева или без статьи — без статьи. Перед записью каждая строка проверяется по инвариантам
Operation; кривая — в отчёт и выход с ошибкой. В конце остатки сверяются с AbilityCash.

    python tools/cash_to_cashbook.py <файл.cash> <выход.db>
"""
import json
import shutil
import sqlite3
import sys
import tempfile
from pathlib import Path

SCHEMA_DIR = Path(__file__).resolve().parent.parent / "android/app/schemas/app.cashbook.data.AppDatabase"


def empty_cashbook(path):
    """Пустая база по последней схеме Room; возвращает соединение."""
    version = max(int(p.stem) for p in SCHEMA_DIR.glob("*.json"))
    schema = json.loads((SCHEMA_DIR / f"{version}.json").read_text(encoding="utf-8"))["database"]
    db = sqlite3.connect(path)
    for e in schema["entities"]:
        db.execute(e["createSql"].replace("${TABLE_NAME}", e["tableName"]))
        for i in e.get("indices", []):
            db.execute(i["createSql"].replace("${TABLE_NAME}", e["tableName"]))
    for q in schema["setupQueries"]:  # room_master_table и identity_hash
        db.execute(q)
    db.execute(f"PRAGMA user_version = {version}")
    return db


def convert(src, out):
    if Path(out).exists():
        raise SystemExit(f"Уже есть {out} — не перезаписываю")
    errors = []
    tmp = Path(tempfile.mkdtemp()) / "source.cash"
    shutil.copy2(src, tmp)  # исходник не открываем вовсе: AbilityCash может держать его открытым
    cash = sqlite3.connect(tmp.as_uri() + "?mode=ro", uri=True)
    q = lambda sql, *a: cash.execute(sql, a).fetchall()
    db = empty_cashbook(out)

    # Валюты — в порядке Id: первая (UAH) — базовая.
    for cid, code, name, precision in q("SELECT Id, Code, Name, Precision FROM Currencies WHERE Deleted = 0 ORDER BY Id"):
        db.execute("INSERT INTO currencies (id, code, name, precision) VALUES (?, ?, ?, ?)",
                   (cid, code.strip()[:3], name.strip(), precision))

    accounts = {}
    for aid, name, start, cur, deleted, comment in q(
            "SELECT Id, Name, StartingBalance, Currency, Deleted, Comment FROM Accounts ORDER BY Id"):
        db.execute("INSERT INTO accounts (id, name, currencyId, startingBalance, comment, archived) VALUES (?, ?, ?, ?, ?, ?)",
                   (aid, name.strip(), cur, start, comment, deleted))
        accounts[aid] = name.strip()

    income_root, expense_root = q("SELECT IncomeTreeRoot, ExpenseTreeRoot FROM Classifiers WHERE Deleted = 0 ORDER BY Id LIMIT 1")[0]
    children = {}
    for cid, name, comment, parent in q("SELECT Id, Name, Comment, Parent FROM Categories WHERE Deleted = 0 ORDER BY Id"):
        children.setdefault(parent, []).append((cid, name, comment))
    kinds = {}

    def walk(parent, kind, out_parent):
        for cid, name, comment in children.get(parent, []):
            db.execute("INSERT INTO categories (id, name, parentId, kind, comment, deleted) VALUES (?, ?, ?, ?, ?, 0)",
                       (cid, name, out_parent, kind, comment))
            kinds[cid] = kind
            walk(cid, kind, cid)
    walk(income_root, "INCOME", None)
    walk(expense_root, "EXPENSE", None)

    rows = q("""
        SELECT t.Id, g.HolderDateTime, g.Position, t.IncomeAccount, t.IncomeAmount, t.ExpenseAccount, t.ExpenseAmount,
            (SELECT tc.Category FROM TransactionCategories tc WHERE tc.[Transaction] = t.Id AND tc.Deleted = 0), t.Comment, t.Changed
        FROM Transactions t JOIN TransactionGroups g ON g.Id = t.[Group]
        WHERE t.Deleted = 0 AND g.Deleted = 0 AND t.Executed = 1
        ORDER BY g.HolderDateTime, g.Position, t.Id""")
    no_category = 0
    for tid, when, pos, inc_acc, inc_amt, exp_acc, exp_amt, cat, comment, changed in rows:
        exp_amt = -exp_amt if exp_amt is not None else None  # в .cash расход отрицательный
        problem = (
            "дата не в полночь UTC" if when % 86400 else
            "нет счёта" if inc_acc is None and exp_acc is None else
            "сторона дохода неполная" if (inc_acc is None) != (inc_amt is None) else
            "сторона расхода неполная" if (exp_acc is None) != (exp_amt is None) else
            "сумма ≤ 0" if (inc_amt is not None and inc_amt <= 0) or (exp_amt is not None and exp_amt <= 0) else
            "перевод на тот же счёт" if inc_acc is not None and inc_acc == exp_acc else
            "неизвестный счёт" if any(a is not None and a not in accounts for a in (inc_acc, exp_acc)) else None
        )
        if problem:
            errors.append(f"операция {tid}: {problem}")
            continue
        transfer = inc_acc is not None and exp_acc is not None
        category = None if transfer or cat not in kinds else cat
        if not transfer and category is None:
            no_category += 1
        db.execute(
            """INSERT INTO operations (id, date, position, expenseAccountId, expenseAmount, incomeAccountId, incomeAmount,
                categoryId, comment, createdAt, baseAmount) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)""",
            (tid, when // 86400, pos, exp_acc, exp_amt, inc_acc, inc_amt, category, comment, changed))

    if errors:
        db.close()
        Path(out).unlink()
        raise SystemExit("Не перенесено, ошибки:\n" + "\n".join(errors))

    # Сверка остатков: то, что посчитает приложение, против суммы по .cash.
    mismatch = []
    for aid, name in accounts.items():
        ours = db.execute("""SELECT a.startingBalance
            + COALESCE((SELECT SUM(incomeAmount) FROM operations WHERE incomeAccountId = a.id), 0)
            - COALESCE((SELECT SUM(expenseAmount) FROM operations WHERE expenseAccountId = a.id), 0)
            FROM accounts a WHERE a.id = ?""", (aid,)).fetchone()[0]
        theirs = q("""SELECT a.StartingBalance
            + COALESCE((SELECT SUM(IncomeAmount) FROM Transactions WHERE Deleted = 0 AND IncomeAccount = a.Id), 0)
            + COALESCE((SELECT SUM(ExpenseAmount) FROM Transactions WHERE Deleted = 0 AND ExpenseAccount = a.Id), 0)
            FROM Accounts a WHERE a.Id = ?""", aid)[0][0]
        print(f"  {name}: {ours / 10000:,.2f}".replace(",", " ") + ("" if ours == theirs else f"  ≠ AbilityCash {theirs / 10000:.2f}"))
        if ours != theirs:
            mismatch.append(name)
    db.commit()
    ok = db.execute("PRAGMA integrity_check").fetchone()[0]
    counts = [db.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0] for t in ("accounts", "categories", "operations")]
    db.close()
    cash.close()
    tmp.unlink()
    if mismatch or ok != "ok":
        Path(out).unlink()
        raise SystemExit(f"Остатки не сошлись ({', '.join(mismatch)}) или база битая ({ok}) — файл удалён")
    print(f"счетов {counts[0]}, статей {counts[1]}, операций {counts[2]} (без статьи: {no_category}) -> {out}")


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    convert(Path(sys.argv[1]).resolve(strict=True), sys.argv[2])


if __name__ == "__main__":
    main()
