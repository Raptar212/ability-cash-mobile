package app.cashbook

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.cashbook.data.AppDatabase
import app.cashbook.data.Backup
import app.cashbook.reminder.Reminder
import app.cashbook.ui.AccountEditScreen
import app.cashbook.ui.AppTheme
import app.cashbook.ui.AppearanceScreen
import app.cashbook.ui.ReminderScreen
import app.cashbook.data.CategoryKind
import app.cashbook.ui.ArchivedAccountsScreen
import app.cashbook.ui.CashbookTheme
import app.cashbook.ui.CategoriesScreen
import app.cashbook.ui.ChartsScreen
import app.cashbook.ui.CoinKeeperCsv
import app.cashbook.ui.EditOperationScreen
import app.cashbook.ui.HomeScreen
import app.cashbook.ui.OperationScreen
import app.cashbook.ui.OperationsScreen
import app.cashbook.ui.OpsFilter
import app.cashbook.ui.Node
import app.cashbook.ui.NodeKind
import app.cashbook.ui.QuickExpenseScreen
import app.cashbook.ui.TransferTargetScreen
import app.cashbook.ui.AccountOrderScreen
import app.cashbook.ui.SettingsItem
import app.cashbook.ui.SettingsScreen
import app.cashbook.ui.UpdatesScreen
import app.cashbook.update.InstallStatusReceiver
import app.cashbook.update.Outcome
import app.cashbook.update.SelfUpdate
import app.cashbook.update.Updates
import app.cashbook.ui.WidgetSettingsScreen
import app.cashbook.widget.BalanceWidget
import app.cashbook.ui.decodeDrop
import app.cashbook.ui.encodeDrop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

class MainActivity : ComponentActivity() {
    /** Открыт главный экран без диалогов — только отсюда обновление ставится молча (несохранённое не пропадёт). */
    @Volatile private var onHome = false

    override fun onResume() {
        super.onResume()
        SelfUpdate.onScreen = true
        SelfUpdate.ownActivity = false // вернулись из своего выбора файла
        InstallStatusReceiver.showConfirm(this) // окно «Обновить?», пришедшее, пока были в фоне
    }

    override fun onPause() {
        SelfUpdate.onScreen = false
        super.onPause()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        SelfUpdate.commitIfIdle(this, onHome)
    }

    override fun onDestroy() {
        if (isFinishing) SelfUpdate.commitIfIdle(this, onHome)
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // До первого кадра — как в системе; дальше стиль баров задаёт выбранная тема (LaunchedEffect ниже).
        val bars = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        super.onCreate(savedInstanceState)
        val importFile = File(cacheDir, "import.db")
        Reminder.schedule(this) // будильник мог пропасть (принудительная остановка) — ставим заново
        setContent {
            // Тема из настроек; значки статус-бара — по ней, а не по системе.
            var theme by remember { mutableStateOf(AppTheme.load(this)) }
            val dark = theme.isDark()
            LaunchedEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            CashbookTheme(dark) {
                // Навигация состоянием (переживает поворот): форма счёта (0 — новый) или ввод операции.
                var editing by rememberSaveable { mutableStateOf<Long?>(null) }
                var drop by rememberSaveable { mutableStateOf<String?>(null) }
                // Лента операций: месяц «2026-11» (null — лента закрыта) и правка операции по id.
                // Месяц и прокрутка здесь, а не в ленте: возврат из правки — на то же место.
                var listMonth by rememberSaveable { mutableStateOf<String?>(null) }
                var editOp by rememberSaveable { mutableStateOf<Long?>(null) }
                // Лента одного счёта / одной статьи расхода (тап по кружку); оба null — все операции.
                var listAccount by rememberSaveable { mutableStateOf<Long?>(null) }
                var listCategory by rememberSaveable { mutableStateOf<Long?>(null) }
                var charts by rememberSaveable { mutableStateOf(false) }
                var widgetSettings by rememberSaveable { mutableStateOf(false) }
                var categoriesKind by rememberSaveable { mutableStateOf<CategoryKind?>(null) } // экран «Статьи»
                var archive by rememberSaveable { mutableStateOf(false) }
                var updatesScreen by rememberSaveable { mutableStateOf(false) }
                // Настройки (☰) — отдельный экран; пункты открываются поверх, «Назад» возвращает в настройки.
                var settings by rememberSaveable { mutableStateOf(false) }
                var section by rememberSaveable { mutableStateOf<String?>(null) } // раздел настроек
                var accountOrder by rememberSaveable { mutableStateOf(false) }
                var updateOutcome by remember { mutableStateOf<Outcome?>(null) } // диалог проверки/установки
                var checking by remember { mutableStateOf(false) }
                // «+» на виджете: id счёта — выбор статьи для нового расхода (только при запуске с виджета).
                var quickAdd by rememberSaveable {
                    mutableStateOf(
                        intent.getLongExtra(BalanceWidget.EXTRA_ADD_ACCOUNT, 0L).takeIf { it > 0 && savedInstanceState == null },
                    )
                }
                var transferFrom by rememberSaveable { mutableStateOf<Long?>(null) } // «→» в ленте счёта: выбор получателя
                val listState = rememberLazyListState()
                var confirmImport by rememberSaveable { mutableStateOf(false) }
                var restoring by rememberSaveable { mutableStateOf(false) }
                // dao берётся заново после восстановления: прежний экземпляр Room мог быть закрыт reset().
                val dao = remember(restoring) { AppDatabase.get(this@MainActivity).dao() }
                val scope = rememberCoroutineScope()
                // «Расход 120 ₴ · Отменить» после записи новой операции: id и текст.
                val snackbar = remember { SnackbarHostState() }
                var undo by remember { mutableStateOf<Pair<Long, String>?>(null) }
                LaunchedEffect(undo, dao) { // dao — новый после восстановления копии
                    val (id, text) = undo ?: return@LaunchedEffect
                    val result = snackbar.showSnackbar(text, actionLabel = "Отменить", duration = SnackbarDuration.Short)
                    if (result == SnackbarResult.ActionPerformed) dao.operation(id)?.let { dao.deleteOperation(it) }
                    undo = null
                }

                fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

                /** Работа с файлами — вне главного потока; ошибка — сообщение, не крэш. */
                fun io(block: suspend () -> String?) = scope.launch {
                    val message = try {
                        withContext(Dispatchers.IO) { block() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        "Ошибка: ${e.message}"
                    }
                    message?.let(::toast)
                }

                val exporter = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/octet-stream"),
                ) { uri ->
                    if (uri != null) io {
                        Backup.export(this, AppDatabase.get(this)) {
                            contentResolver.openOutputStream(uri) ?: error("Не открыть файл для записи")
                        }
                        "Копия базы сохранена"
                    }
                }
                val csvExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
                    if (uri != null) io {
                        val ops = dao.allOperations()
                        val csv = CoinKeeperCsv.write(ops, dao.allAccounts().first(), dao.currencies().first(), dao.allCategories().first())
                        val out = contentResolver.openOutputStream(uri) ?: error("Не открыть файл для записи")
                        out.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
                        "CSV сохранён: операций ${ops.size}"
                    }
                }
                val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) io {
                        try {
                            val input = contentResolver.openInputStream(uri) ?: error("Не открыть файл")
                            input.use { Backup.receive(it, importFile) }
                            val problem = Backup.check(importFile, Backup.identityHash(AppDatabase.get(this)))
                            if (problem == null) confirmImport = true else Backup.discard(importFile)
                            problem
                        } catch (e: Exception) {
                            Backup.discard(importFile) // копия данных не остаётся в кэше
                            throw e
                        }
                    }
                }

                val accountId = editing
                val operation = drop
                val opId = editOp
                val month = listMonth
                // Экран «Обновления» несохранённого ввода не держит — с него «Обновить» ставит сразу.
                val home = !restoring && quickAdd == null && transferFrom == null && categoriesKind == null && !archive && !widgetSettings &&
                    accountId == null && operation == null && opId == null && !charts && month == null
                // Настройки и «Порядок счетов» тоже без несохранённого ввода — флаги settings/accountOrder
                // сюда не входят: иначе «Обновить» на экране «Обновления» (открыт из настроек) не ставил бы сразу.
                SideEffect { onHome = home && !confirmImport && updateOutcome == null }

                fun checkUpdates(manual: Boolean) = scope.launch {
                    if (manual) checking = true
                    try {
                        Updates.check(this@MainActivity, manual) { onHome }?.let { updateOutcome = it }
                    } finally {
                        checking = false
                    }
                }
                LaunchedEffect(Unit) { checkUpdates(manual = false) } // не чаще раза в день — решает Updates

                when {
                    restoring -> {
                        // Экраны с базой ушли из композиции — их запросы остановлены; меняем файл.
                        // Флаг не сбрасываем до пересоздания: старые экраны со старым dao не
                        // всплывают. В новой Activity restore() найдёт, что файла уже нет, и
                        // вернёт false — тогда показываем главный экран.
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Восстанавливаю копию…") }
                        LaunchedEffect(Unit) {
                            // null — файла импорта нет (восстановление уже прошло): просто вернуться.
                            val outcome: String? = try {
                                val done = withContext(Dispatchers.IO) { Backup.restore(this@MainActivity, importFile) }
                                if (done) "Данные восстановлены из копии" else null
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // move — последний шаг: при любой ошибке рабочая база не изменена.
                                withContext(Dispatchers.IO) { Backup.discard(importFile) }
                                "Не удалось восстановить: ${e.message}. Данные на телефоне не изменены"
                            }
                            if (outcome != null) {
                                toast(outcome)
                                recreate() // база могла быть закрыта — экраны откроют её заново
                            } else {
                                restoring = false
                            }
                        }
                    }
                    quickAdd != null -> {
                        BackHandler { quickAdd = null }
                        QuickExpenseScreen(
                            dao,
                            quickAdd!!,
                            onPick = { account, category ->
                                drop = encodeDrop(Node(NodeKind.ACCOUNT, account), Node(NodeKind.EXPENSE, category))
                                quickAdd = null
                            },
                            onBack = { quickAdd = null },
                        )
                    }
                    transferFrom != null -> {
                        val fromId = transferFrom!!
                        BackHandler { transferFrom = null }
                        TransferTargetScreen(
                            dao,
                            fromId,
                            onPick = { to ->
                                drop = encodeDrop(Node(NodeKind.ACCOUNT, fromId), Node(NodeKind.ACCOUNT, to))
                                transferFrom = null
                            },
                            onBack = { transferFrom = null },
                        )
                    }
                    categoriesKind != null -> {
                        BackHandler { categoriesKind = null }
                        CategoriesScreen(dao, categoriesKind!!, onBack = { categoriesKind = null })
                    }
                    archive -> {
                        BackHandler { archive = false }
                        ArchivedAccountsScreen(dao, onBack = { archive = false })
                    }
                    updatesScreen -> {
                        BackHandler { updatesScreen = false }
                        UpdatesScreen(checking, onCheck = { checkUpdates(manual = true) }, onBack = { updatesScreen = false })
                    }
                    accountOrder -> {
                        BackHandler { accountOrder = false }
                        AccountOrderScreen(dao, onBack = { accountOrder = false })
                    }
                    widgetSettings -> {
                        BackHandler { widgetSettings = false }
                        WidgetSettingsScreen(dao, onBack = { widgetSettings = false })
                    }
                    accountId != null -> {
                        BackHandler { editing = null }
                        AccountEditScreen(dao, accountId, onDone = { editing = null })
                    }
                    operation != null -> {
                        BackHandler { drop = null }
                        val (from, to) = decodeDrop(operation)
                        OperationScreen(dao, from, to, onDone = { drop = null }, onSaved = { id, text -> undo = id to text })
                    }
                    opId != null -> {
                        BackHandler { editOp = null }
                        EditOperationScreen(dao, opId, onDone = { editOp = null })
                    }
                    charts -> {
                        BackHandler { charts = false }
                        ChartsScreen(dao, onBack = { charts = false })
                    }
                    month != null -> {
                        fun close() {
                            listMonth = null
                            listAccount = null
                            listCategory = null
                        }
                        BackHandler { close() }
                        val acc = listAccount
                        val cat = listCategory
                        OperationsScreen(
                            dao,
                            YearMonth.parse(month),
                            listState,
                            onMonth = { listMonth = it.toString() },
                            onOpen = { editOp = it },
                            onBack = { close() },
                            filter = when {
                                acc != null -> OpsFilter.Account(acc)
                                cat != null -> OpsFilter.Category(cat)
                                else -> OpsFilter.All
                            },
                            // «+» на счёте — выбор статьи расхода, как с виджета.
                            onAdd = when {
                                acc != null -> { { quickAdd = acc } }
                                // На статье — расход со счёта последнего расхода (нет — первый счёт).
                                cat != null -> { {
                                    scope.launch {
                                        val from = dao.lastAccount(true) ?: dao.balances().first().firstOrNull()?.accountId
                                        if (from != null) drop = encodeDrop(Node(NodeKind.ACCOUNT, from), Node(NodeKind.EXPENSE, cat))
                                    }
                                } }
                                else -> null
                            },
                            onEdit = acc?.let { { editing = it } },
                            onTransfer = acc?.let { { transferFrom = it } },
                        )
                    }
                    settings -> {
                        // Раздел открыт поверх списка разделов: «Назад» — в список, оттуда — на главный.
                        BackHandler { if (section != null) section = null else settings = false }
                        val back = { section = null }
                        when (section) {
                            "appearance" -> AppearanceScreen(
                                theme,
                                onTheme = {
                                    theme = it
                                    AppTheme.save(this, it)
                                },
                                onWidget = { widgetSettings = true },
                                onBack = back,
                            )
                            "reminder" -> ReminderScreen(onBack = back)
                            "accounting" -> SettingsScreen(
                                "Учёт",
                                listOf(
                                    SettingsItem("Статьи") { categoriesKind = CategoryKind.EXPENSE },
                                    SettingsItem("Порядок счетов") { accountOrder = true },
                                    SettingsItem("Архив счетов") { archive = true },
                                ),
                                onBack = back,
                            )
                            "data" -> SettingsScreen(
                                "Данные",
                                listOfNotNull(
                                    // Свой выбор файла — не уход из приложения: обновление не ставится поверх него.
                                    SettingsItem("Экспорт копии базы") {
                                        SelfUpdate.ownActivity = true
                                        exporter.launch("cashbook-${LocalDate.now()}.db")
                                    },
                                    SettingsItem("Импорт копии базы") {
                                        SelfUpdate.ownActivity = true
                                        importer.launch(arrayOf("*/*"))
                                    },
                                    if (CoinKeeperCsv.ENABLED) SettingsItem("Экспорт CSV (CoinKeeper)") {
                                        SelfUpdate.ownActivity = true
                                        csvExporter.launch("coinkeeper-${LocalDate.now()}.csv")
                                    } else null,
                                ),
                                onBack = back,
                            )
                            else -> SettingsScreen(
                                "Настройки",
                                listOf(
                                    SettingsItem("Графики", "Расходы и доходы по статьям и месяцам") { charts = true },
                                    SettingsItem("Внешний вид", "Тема: ${theme.title.lowercase()}, виджет") { section = "appearance" },
                                    SettingsItem("Учёт", "Статьи, порядок и архив счетов") { section = "accounting" },
                                    SettingsItem(
                                        "Напоминание",
                                        Reminder.time(this).takeIf { Reminder.enabled(this) && Reminder.permitted(this) }
                                            ?.let { "Каждый день в %02d:%02d".format(it.hour, it.minute) } ?: "Выключено",
                                    ) { section = "reminder" },
                                    SettingsItem("Данные", if (CoinKeeperCsv.ENABLED) "Копия базы, экспорт CSV" else "Копия базы") { section = "data" },
                                    SettingsItem("Обновления", "Версия ${Updates.current(this)}") { updatesScreen = true },
                                ),
                                onBack = { settings = false },
                            )
                        }
                    }
                    else -> HomeScreen(
                        dao,
                        onAccount = {
                            listAccount = it
                            listMonth = YearMonth.now().toString()
                            listState.requestScrollToItem(0)
                        },
                        onExpenseCategory = {
                            listCategory = it
                            listMonth = YearMonth.now().toString()
                            listState.requestScrollToItem(0)
                        },
                        onNewAccount = { editing = 0L },
                        onDrop = { from, to -> drop = encodeDrop(from, to) },
                        onSettings = { settings = true },
                        onOperations = {
                            listMonth = YearMonth.now().toString()
                            listState.requestScrollToItem(0) // с главного — сверху; из правки — на прежнее место
                        },
                    )
                }

                Box(Modifier.fillMaxSize().navigationBarsPadding(), contentAlignment = Alignment.BottomCenter) {
                    SnackbarHost(snackbar)
                }

                when (val o = updateOutcome) {
                    null -> Unit
                    is Outcome.Available -> AlertDialog(
                        onDismissRequest = { updateOutcome = null },
                        title = { Text("Доступна версия ${o.release.version}") },
                        text = {
                            Text(
                                "Установлена ${o.current}. «Обновить» скачает новую версию и установит её; приложение " +
                                    "закроется на несколько секунд. Данные сохранятся.",
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                updateOutcome = null
                                if (SelfUpdate.canInstall(this@MainActivity)) toast("Загружаю обновление…")
                                scope.launch { Updates.install(this@MainActivity, o.release) { onHome }?.let { updateOutcome = it } }
                            }) { Text("Обновить") }
                        },
                        dismissButton = { TextButton(onClick = { updateOutcome = null }) { Text("Позже") } },
                    )
                    is Outcome.NeedPermission -> AlertDialog(
                        onDismissRequest = { updateOutcome = null },
                        title = { Text("Нужно разрешение") },
                        text = {
                            Text(
                                "Чтобы приложение само ставило свои обновления, включите для него «Установку " +
                                    "неизвестных приложений» (один раз). Потом снова ☰ → «Обновления» → «Проверить сейчас».",
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                updateOutcome = null
                                SelfUpdate.askPermission(this@MainActivity)
                            }) { Text("Открыть настройки") }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                updateOutcome = null
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(o.release.url)))
                            }) { Text("Через браузер") }
                        },
                    )
                    is Outcome.Message -> AlertDialog(
                        onDismissRequest = { updateOutcome = null },
                        title = { Text(o.title) },
                        text = { Text(o.text) },
                        confirmButton = { TextButton(onClick = { updateOutcome = null }) { Text("OK") } },
                    )
                }

                if (confirmImport) {
                    AlertDialog(
                        onDismissRequest = {
                            confirmImport = false
                            Backup.discard(importFile)
                        },
                        title = { Text("Заменить данные?") },
                        text = {
                            Text(
                                "Все счета и операции на телефоне заменятся данными из файла. " +
                                    "Текущие данные сохранятся в резервную копию внутри приложения.",
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmImport = false
                                editing = null
                                drop = null
                                restoring = true
                            }) { Text("Заменить") }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                confirmImport = false
                                Backup.discard(importFile) // копия финансовых данных не лежит в кэше
                            }) { Text("Отмена") }
                        },
                    )
                }
            }
        }
    }
}
