package com.kuva.vpn.ui.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kuva.vpn.R

/**
 * In-app privacy policy viewer. The full text ships as a bundled asset
 * (privacy_en.md / privacy_fa.md) so the policy is available offline and
 * always matches the app build. Same document as docs/PRIVACY.md in the repo.
 */
@Composable
fun PrivacyPolicyDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val isFa = AppCompatDelegate.getApplicationLocales()
        .toLanguageTags().startsWith("fa")
    val text = remember(isFa) {
        val primary = if (isFa) "privacy_fa.md" else "privacy_en.md"
        try {
            context.assets.open(primary).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            try {
                context.assets.open("privacy_en.md").bufferedReader().use { it.readText() }
            } catch (e2: Exception) {
                "Privacy policy file missing from this build."
            }
        }
    }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                Modifier
                    .padding(20.dp)
                    .heightIn(max = 640.dp),
            ) {
                Text(
                    stringResource(R.string.privacy),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 12.dp),
                )
                TextButton(
                    onClick = onClose,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(stringResource(R.string.close))
                }
            }
        }
    }
}
