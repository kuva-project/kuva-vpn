package com.kuva.vpn.ui.share

import android.content.Intent
import androidx.core.content.FileProvider
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.kuva.vpn.R
import com.kuva.vpn.core.qr.QrCode
import com.kuva.vpn.data.settings.SettingsRepository
import android.net.Uri
import java.io.File

/**
 * Popup showing the QR code of a config/subscription link with
 * copy-to-clipboard and system share actions underneath.
 */
@Composable
fun QrShareDialog(
    link: String,
    title: String,
    onDismiss: () -> Unit,
    exportJson: (() -> String)? = null,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    // side of the bitmap in px — rendered at 260dp on screen
    val qr: Bitmap = remember(link) { QrCode.bitmap(link, size = 720) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // header: title + close
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(Modifier.height(10.dp))

                Image(
                    bitmap = qr.asImageBitmap(),
                    contentDescription = "QR code",
                    modifier = Modifier
                        .size(210.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(androidx.compose.ui.graphics.Color.White)
                        .padding(8.dp),
                )

                Spacer(Modifier.height(12.dp))

                // the link itself (readable, single line, ellipsized)
                Text(
                    link,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(link))
                        Toast.makeText(
                            context,
                            context.getString(R.string.copied_to_clipboard),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Copy")
                    }
                    TextButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, link)
                        }
                        context.startActivity(
                            Intent.createChooser(send, title),
                        )
                    }) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Share")
                    }
                    // Export JSON: write the REAL runtime Xray config (identical
                    // to what the core runs with the current settings) to
                    // Downloads and offer it via the system share sheet.
                    if (exportJson != null) {
                        TextButton(onClick = {
                            try {
                                val json = exportJson!!.invoke()
                                // cache/exports is exposed in file_paths.xml
                                val dir = File(context.cacheDir, "exports")
                                if (!dir.exists()) dir.mkdirs()
                                val safe = title.replace(Regex("[^A-Za-z0-9_-]"), "_").takeLast(40)
                                val f = File(dir, "kuva_${'$'}safe.json")
                                f.writeText(json)
                                val uri = FileProvider.getUriForFile(
                                    context, context.packageName + ".fileprovider", f
                                )
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/json"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(send, "Export JSON"))
                                Toast.makeText(context, f.name, Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Export failed: " + e.message, Toast.LENGTH_LONG).show()
                            }
                        }) {
                            Icon(
                                Icons.Default.Share,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Export JSON")
                        }
                    }
                }
            }
        }
    }
}

/** Small header action that opens the QR dialog (user-designed share icon). */
@Composable
fun ShareIconButton(resId: Int = R.drawable.ic_share, contentDesc: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Image(
            painter = painterResource(resId),
            contentDescription = contentDesc,
            modifier = Modifier.size(32.dp),
        )
    }
}
