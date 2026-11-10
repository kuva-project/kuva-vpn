package com.kuva.vpn.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/**
 * Offline country flag: bundled PNG for ~96 countries (res/drawable/flag_XX.png,
 * w40 sources from flagcdn.com), emoji fallback for anything else — so the
 * dashboard flag never waits on a network image again.
 */
@Composable
fun CountryFlag(
    iso: String?,
    modifier: Modifier = Modifier,
    width: Dp = 21.dp,
    height: Dp = 15.dp,
    emojiFontSize: TextUnit = MaterialTheme.typography.bodyMedium.fontSize,
) {
    val context = LocalContext.current
    val norm = iso?.trim()?.uppercase()?.take(2).orEmpty()
    val valid = norm.length == 2 && norm.all { it in 'A'..'Z' }

    @DrawableRes
    val resId = remember(norm) {
        if (!valid) 0
        else runCatching {
            context.resources.getIdentifier(
                "flag_${norm.lowercase()}", "drawable", context.packageName
            )
        }.getOrDefault(0)
    }

    when {
        resId != 0 -> Image(
            painter = painterResource(resId),
            contentDescription = null,
            modifier = modifier
                .size(width = width, height = height)
                .clip(RoundedCornerShape(3.dp)),
            contentScale = ContentScale.Crop,
        )
        valid -> Box(
            modifier = modifier.size(width = width, height = height),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = norm.map { regionalIndicator(it) }.joinToString(""),
                fontSize = emojiFontSize,
            )
        }
        // no ISO yet: emit NOTHING so the value text stays flush with the
        // column above (e.g. "Location unverified" aligns with "Unavailable")
    }
}

/** 'D','E' -> "🇩🇪" via Unicode regional-indicator symbols (U+1F1E6..U+1F1FF). */
private fun regionalIndicator(c: Char): String = String(
    Character.toChars(0x1F1E6 + (c - 'A'))
)
