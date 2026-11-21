# Cashbook

Офлайн-учёт денег для Android в духе CoinKeeper: кружки счетов и статей, ввод перетаскиванием,
лента операций, графики и виджет с остатком. Дерево статей можно взять из AbilityCash на ПК, а
экспорт CSV — в формате CoinKeeper, поэтому операции можно переносить на компьютер вручную.

## Установка

Android 8.0 и новее: скачайте `app-release.apk` из
[Releases](https://github.com/Raptar212/ability-cash-mobile/releases) и откройте его на телефоне
(один раз разрешите установку из этого источника). Данные хранятся только на телефоне.

## Что умеет

- Счета в разных валютах, расход, доход, перевод и обмен; курс подставляется из прошлой операции.
- Дерево статей с подстатьями; при первом запуске — стартовый набор, дальше правится в приложении.
- Графики: расходы и доходы по статьям за месяц и по месяцам.
- Виджет «Баланс» на рабочем столе с быстрым расходом.
- Меню ☰: экспорт и импорт полной копии базы, экспорт CSV в формате CoinKeeper.

## Перенос из AbilityCash

`tools/cash_to_cashbook.py` собирает из копии `.cash` файл базы Cashbook (счета, статьи, все
операции) и сверяет остатки с AbilityCash; исходный файл не меняется. Полученный файл загрузите на
телефон через ☰ → «Импорт копии базы».

```sh
python tools/cash_to_cashbook.py мой.cash cashbook.db
```

## Сборка

JDK 17+ и Android SDK (путь — в `android/local.properties`).

```sh
cd android
./gradlew testDebugUnitTest assembleDebug   # тесты и отладочная сборка (app.cashbook.debug)
./gradlew assembleRelease                   # release; подпись — свойства cashbookStoreFile,
                                            # cashbookStorePassword, cashbookKeyAlias, cashbookKeyPassword
                                            # в ~/.gradle/gradle.properties
```

## Лицензия

[MIT](LICENSE).
