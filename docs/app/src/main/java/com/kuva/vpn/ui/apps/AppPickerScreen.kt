package com.kuva.vpn.ui.apps

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kuva.vpn.R

/**
 * Installed-app picker for split tunneling. Multi-select with search,
 * select-all / clear, icons. Result persisted as a package-name set.
 */
@Composable
fun AppPickerScreen(
    selected: Set<String>,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var apps by remember { mutableStateOf<List<ApplicationInfo>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var checked by remember { mutableStateOf(selected.toMutableSet()) }
    val pm = context.packageManager

    LaunchedEffect(Unit) {
        apps = pm.getInstalledApplications(0)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 || true } // include all, searchable
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
    }

    val filtered = apps.filter {
        query.isBlank() || pm.getApplicationLabel(it).toString().contains(query, true) ||
            it.packageName.contains(query, true)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_apps)) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { checked = apps.map { it.packageName }.toMutableSet() },
                        label = { Text(stringResource(R.string.select_all)) })
                    AssistChip(onClick = { checked.clear() },
                        label = { Text(stringResource(R.string.clear_selection)) })
                }
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    singleLine = true,
                )
                Text(stringResource(R.string.apps_count, checked.size),
                    style = MaterialTheme.typography.labelSmall)
                LazyColumn(modifier = Modifier.height(340.dp)) {
                    items(filtered) { app ->
                        val pkg = app.packageName
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val icon = remember(pkg) {
                                runCatching { pm.getApplicationIcon(pkg) }.getOrNull()
                            }
                            val bmp = remember(pkg) {
                                (icon as? android.graphics.drawable.BitmapDrawable)?.bitmap
                            }
                            bmp?.let {
                                Image(
                                    bitmap = it.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.size(34.dp),
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(pm.getApplicationLabel(app).toString(),
                                    style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                Text(pkg, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            Checkbox(
                                checked = pkg in checked,
                                onCheckedChange = { on ->
                                    if (on) checked.add(pkg) else checked.remove(pkg)
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(checked.toSet()) }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
