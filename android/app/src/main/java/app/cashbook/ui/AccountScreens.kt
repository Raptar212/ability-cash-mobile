package app.cashbook.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.cashbook.data.Account
import app.cashbook.data.CashDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Форма счёта: [accountId] = 0 — новый счёт. Валюту существующего счёта не меняем: суммы операций в ней. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountEditScreen(dao: CashDao, accountId: Long, onDone: () -> Unit) {
    val currencies by remember { dao.currencies() }.collectAsState(initial = emptyList())
    var existing by remember { mutableStateOf<Account?>(null) }
    // Ввод пользователя — rememberSaveable: переживает поворот; existing перечитывается из БД.
    var draft by rememberSaveable(stateSaver = DraftSaver) { mutableStateOf(AccountDraft()) }
    var loaded by rememberSaveable { mutableStateOf(false) }
    var errors by rememberSaveable(stateSaver = ErrorsSaver) { mutableStateOf(Validated.Errors()) }
    var saving by remember { mutableStateOf(false) }
    var confirmArchive by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(accountId, currencies) {
        if (currencies.isEmpty()) return@LaunchedEffect
        if (accountId == 0L) {
            if (!loaded) draft = AccountDraft(currencyId = currencies.first().id)
        } else {
            val a = dao.account(accountId) ?: return@LaunchedEffect onDone()
            existing = a
            if (!loaded) draft = AccountDraft.of(a, currencies.find { it.id == a.currencyId }?.precision ?: 2)
        }
        loaded = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (accountId == 0L) "Новый счёт" else "Счёт") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { padding ->
        if (!loaded || (accountId != 0L && existing == null)) return@Scaffold
        val d = draft
        Column(
            Modifier.padding(padding).imePadding().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = d.name,
                onValueChange = { draft = d.copy(name = it) },
                label = { Text("Название") },
                isError = errors.name != null,
                supportingText = { errors.name?.let { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Валюта", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (c in currencies) {
                    FilterChip(
                        selected = d.currencyId == c.id,
                        onClick = { draft = d.copy(currencyId = c.id) },
                        label = { Text(c.code) },
                        enabled = existing == null,
                    )
                }
            }
            errors.currency?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (existing != null) {
                Text(
                    "Валюту счёта не сменить: в ней его операции",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = d.balance,
                onValueChange = { draft = d.copy(balance = it) },
                label = { Text("Начальный остаток") },
                isError = errors.balance != null,
                supportingText = { errors.balance?.let { Text(it) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = d.comment,
                onValueChange = { draft = d.copy(comment = it) },
                label = { Text("Комментарий") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    if (saving) return@Button // повторный клик в том же кадре — без дубля
                    when (val v = d.validate(currencies, existing)) {
                        is Validated.Errors -> errors = v
                        is Validated.Ok -> {
                            saving = true
                            scope.launch {
                                try {
                                    if (v.account.id == 0L) dao.insertAccount(v.account) else dao.updateAccount(v.account)
                                    onDone() // saving не сбрасываем: экран закрывается
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    saving = false
                                    saveError = "Не удалось сохранить: ${e.message}"
                                }
                            }
                        }
                    }
                },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Сохранить") }
            saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            existing?.let {
                OutlinedButton(
                    onClick = { confirmArchive = true },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("В архив") }
            }
        }
    }

    existing?.takeIf { confirmArchive }?.let { a ->
        AlertDialog(
            onDismissRequest = { confirmArchive = false },
            title = { Text("Убрать «${a.name}» в архив?") },
            text = { Text("Счёт пропадёт с главного экрана, операции останутся. Вернуть — меню → «Архив счетов».") },
            dismissButton = { TextButton(onClick = { confirmArchive = false }) { Text("Отмена") } },
            confirmButton = {
                TextButton(onClick = {
                    confirmArchive = false
                    if (saving) return@TextButton
                    saving = true
                    scope.launch {
                        try {
                            dao.updateAccount(a.copy(archived = true))
                            onDone()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            saving = false
                            saveError = "Не удалось сохранить: ${e.message}"
                        }
                    }
                }) { Text("В архив") }
            },
        )
    }
}
