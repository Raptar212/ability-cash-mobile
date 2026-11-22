package app.cashbook.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.cashbook.data.CashDao
import app.cashbook.data.Category
import app.cashbook.data.CategoryKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Что делаем со статьёй в диалоге: новая (верхняя при [parent] = null), подстатья, переименование. */
private sealed interface CategoryEdit {
    data class New(val parent: Category?) : CategoryEdit
    data class Rename(val category: Category) : CategoryEdit
}

/**
 * Меню → «Статьи»: дерево статей вида [initialKind]. Тап — переименовать, добавить подстатью,
 * удалить. Удаляется только статья без операций (вместе с подстатьями) — иначе пропали бы суммы.
 * Дерево на ПК (AbilityCash) правится отдельно: перенос операций ручной.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(dao: CashDao, initialKind: CategoryKind, onBack: () -> Unit) {
    val all by remember { dao.allCategories() }.collectAsState(initial = null)
    var kind by rememberSaveable { mutableStateOf(initialKind) }
    var chosen by remember { mutableStateOf<Category?>(null) } // меню действий
    var edit by remember { mutableStateOf<CategoryEdit?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun run(block: suspend () -> Unit) = scope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message = "Не удалось: ${e.message}"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                title = { Text("Статьи") },
                actions = { IconButton(onClick = { edit = CategoryEdit.New(null) }) { Icon(Icons.Filled.Add, "Новая статья") } },
            )
        },
    ) { padding ->
        val cats = all ?: return@Scaffold
        val tree = cats.filter { it.kind == kind && it.parentId == null }.sortedWith(ByName)
            .flatMap { top -> listOf(top to 0) + descendants(cats, top.id).map { it.first to it.second + 1 } }
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(kind == CategoryKind.EXPENSE, { kind = CategoryKind.EXPENSE }, { Text("Расходы") })
                FilterChip(kind == CategoryKind.INCOME, { kind = CategoryKind.INCOME }, { Text("Доходы") })
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp, 4.dp)) }
            LazyColumn {
                items(tree, key = { it.first.id }) { (c, depth) ->
                    // Иерархия: старшая статья — на тёмном фоне во всю ширину, подстатьи — светлые с отступом
                    // и «›» перед именем (вложенность глубже — отступ больше).
                    ListItem(
                        headlineContent = {
                            if (depth == 0) {
                                Text(c.name, style = MaterialTheme.typography.titleMedium)
                            } else {
                                Text("› ${c.name}", style = MaterialTheme.typography.bodyLarge)
                            }
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = if (depth == 0) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                        ),
                        modifier = Modifier.clickable { chosen = c }.padding(start = (depth * 24).dp),
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    chosen?.let { c ->
        AlertDialog(
            onDismissRequest = { chosen = null },
            title = { Text(c.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { chosen = null; edit = CategoryEdit.Rename(c) }, Modifier.fillMaxWidth()) { Text("Переименовать") }
                    OutlinedButton(onClick = { chosen = null; edit = CategoryEdit.New(c) }, Modifier.fillMaxWidth()) { Text("Добавить подстатью") }
                    OutlinedButton(
                        onClick = {
                            chosen = null
                            run {
                                val ids = listOf(c.id) + descendants(all.orEmpty(), c.id).map { it.first.id }
                                val used = dao.countOperations(ids)
                                if (used > 0) {
                                    message = "«${c.name}» не удалена: в ней и подстатьях операций — $used"
                                } else {
                                    dao.deleteCategories(ids)
                                    message = null
                                }
                            }
                        },
                        Modifier.fillMaxWidth(),
                    ) { Text("Удалить") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { chosen = null }) { Text("Закрыть") } },
        )
    }

    edit?.let { e ->
        val title = when (e) {
            is CategoryEdit.Rename -> "Переименовать"
            is CategoryEdit.New -> e.parent?.let { "Подстатья в «${it.name}»" } ?: "Новая статья"
        }
        NameDialog(title, if (e is CategoryEdit.Rename) e.category.name else "", onDismiss = { edit = null }) { n ->
            edit = null
            run {
                when (e) {
                    is CategoryEdit.Rename -> dao.updateCategory(e.category.copy(name = n))
                    is CategoryEdit.New -> dao.insertCategory(Category(name = n, parentId = e.parent?.id, kind = e.parent?.kind ?: kind))
                }
            }
        }
    }
}

/** Диалог названия (статья): [onSave] получает название без пробелов по краям, пустое не сохраняется. */
@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable(title) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Название") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim()) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** Меню → «Архив счетов»: архивные счета, «Вернуть» — снова активный. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedAccountsScreen(dao: CashDao, onBack: () -> Unit) {
    val archived by remember { dao.archivedAccounts() }.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                title = { Text("Архив счетов") },
            )
        },
    ) { padding ->
        val list = archived ?: return@Scaffold
        if (list.isEmpty()) {
            Text("Архив пуст", Modifier.padding(padding).padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding)) {
            items(list, key = { it.id }) { a ->
                ListItem(
                    headlineContent = { Text(a.name) },
                    supportingContent = a.comment.takeIf { it.isNotBlank() }?.let { { Text(it) } },
                    trailingContent = {
                        TextButton(onClick = { scope.launch { dao.updateAccount(a.copy(archived = false)) } }) { Text("Вернуть") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                HorizontalDivider()
            }
        }
    }
}
