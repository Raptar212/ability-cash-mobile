package app.cashbook.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.cashbook.update.SelfUpdate
import app.cashbook.update.Updates

/** Меню → «Обновления»: версия, автопроверка, тихая установка и проверка сейчас ([onCheck]). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatesScreen(checking: Boolean, onCheck: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(Updates.enabled(context)) }
    var silent by remember { mutableStateOf(Updates.silent(context)) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                title = { Text("Обновления") },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                "Установлена версия ${Updates.current(context)}. Новые версии — в релизах GitHub; данные при обновлении сохраняются.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            SwitchRow("Проверять при открытии", "Не чаще раза в день", enabled) {
                enabled = it
                Updates.setEnabled(context, it)
            }
            if (SelfUpdate.silentSupported) {
                SwitchRow("Устанавливать без вопросов", "По Wi-Fi, когда вы выходите из приложения", silent) {
                    silent = it
                    Updates.setSilent(context, it)
                }
            }
            Button(
                onClick = onCheck,
                enabled = !checking,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) { Text(if (checking) "Проверяю…" else "Проверить сейчас") }
        }
    }
}
