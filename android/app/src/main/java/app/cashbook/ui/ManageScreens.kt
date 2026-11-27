package app.cashbook.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.ui.text.font.FontWeight
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
    var iconFor by remember { mutableStateOf<Category?>(null) } // выбор иконки верхней статьи
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
                    // Иерархия без заливки: старшая статья — жирнее, подстатьи — с отступом и «›» перед именем
                    // (вложенность глубже — отступ больше). Заливка сплошь шла стеной, когда подстатей нет.
                    ListItem(
                        headlineContent = {
                            if (depth == 0) {
                                Text(c.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            } else {
                                Text("› ${c.name}", style = MaterialTheme.typography.bodyLarge)
                            }
                        },
                        // Иконка — у верхней статьи (её кружок на главном); подстатьи без неё.
                        leadingContent = categoryIcon(c.icon)?.takeIf { depth == 0 }?.let { { Icon(it, null) } },
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
                    if (c.parentId == null) {
                        OutlinedButton(onClick = { chosen = null; iconFor = c }, Modifier.fillMaxWidth()) { Text("Иконка") }
                    }
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

    // Выбор иконки: кружки цвета вида, как на главном; тап — сохранить сразу.
    iconFor?.let { c ->
        val color = if (c.kind == CategoryKind.EXPENSE) ExpenseColor else IncomeColor
        fun save(slug: String?) {
            iconFor = null
            run { dao.setCategoryIcon(c.id, slug) }
        }
        AlertDialog(
            onDismissRequest = { iconFor = null },
            title = { Text("Иконка: ${c.name}") },
            text = {
                LazyVerticalGrid(GridCells.Adaptive(56.dp), Modifier.heightIn(max = 360.dp)) {
                    gridItems(CATEGORY_ICON_PATHS.keys.toList()) { slug ->
                        val selected = slug == c.icon
                        Box(
                            // wrapContentSize: ячейка сетки шире 48dp — кружок не растягивается в «таблетку».
                            Modifier.padding(4.dp).wrapContentSize().size(48.dp)
                                .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
                                .clip(CircleShape).background(color)
                                // Переключатель: TalkBack говорит название, «выбрано» и что это выбор.
                                .selectable(selected, role = Role.RadioButton) { save(slug) }
                                .semantics { contentDescription = CATEGORY_ICON_NAMES[slug] ?: slug },
                            contentAlignment = Alignment.Center,
                        ) {
                            categoryIcon(slug)?.let { Icon(it, null, tint = onCircle(color), modifier = Modifier.size(26.dp)) }
                        }
                    }
                }
            },
            confirmButton = { if (c.icon != null) TextButton(onClick = { save(null) }) { Text("Без иконки") } },
            dismissButton = { TextButton(onClick = { iconFor = null }) { Text("Отмена") } },
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
