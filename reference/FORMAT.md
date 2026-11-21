# Формат записи операции в .cash (AbilityCash build 270)

Снято эталоном 2026-11-21: `reference/before.cash` → 5 операций, добавленных в AbilityCash
(расход ×2, доход, перевод, расход задним числом). Схема и `user_version` не меняются,
`journal_mode=delete`. Пока программа открыта, база заблокирована целиком (`database is locked`).

## На одну операцию программа пишет

1. `TransactionGroups` — одна строка:
   - `Guid` — 16 случайных байт (UUID v4, порядок байт Windows / `bytes_le`);
   - `Changed` — Unix-время создания, секунды, UTC;
   - `Deleted=0`;
   - `HolderDateTime` — дата операции: **локальная** дата как полночь UTC
     (21.11.2026 → `1795219200`);
   - `Position` — `max(Position)` за этот день + 1 (пропуски допустимы, повторов нет);
   - `Recurrence='{}'`.
2. `Transactions` — одна строка на группу:
   - `Group` — id группы, `Position=0`;
   - `BudgetDate` = `HolderDateTime`, `BudgetPeriodEnd` = `HolderDateTime + 86400`;
   - `Executed=1`, `Locked=0`, `Quantity=10000`, `ExtraComment1..4=''`, `Comment` — текст;
   - расход: `Expense*` заполнены, `Income*` = NULL, `ExpenseAmount < 0`;
   - доход: `Income*` заполнены, `Expense*` = NULL, `IncomeAmount > 0`;
   - перевод: заполнены обе стороны (`-X` и `+X` в одной валюте; при обмене — суммы в своих валютах).
3. `TransactionCategories` — 0 или 1 строка (`Category`, `Transaction`); у перевода из эталона статьи нет.
4. `sqlite_sequence` — обычный AUTOINCREMENT, SQLite ведёт сам.
5. `Properties.Changed` у всех ключей, кроме `FileGUID` и `CreatedByBuild`, → время сохранения.
6. `InterfacePages.Settings` — состояние окна; при слиянии **не трогать**.

## Деньги и остатки

- Суммы и остатки — целые ×10000 (`-100.00` → `-1000000`).
- `ExpenseBalance` / `IncomeBalance` — остаток счёта **после** операции:
  `StartingBalance + Σ сумм` по счёту в порядке
  `TransactionGroups.HolderDateTime, TransactionGroups.Position, Transactions.Position`,
  без строк с `Deleted=1`. Сверено: 0 расхождений по всем 5 счетам (2919 проводок).
- Операция задним числом → пересчитать остатки всех последующих операций этого счёта.
  Пересчёт остатка `Changed` строки не меняет.
