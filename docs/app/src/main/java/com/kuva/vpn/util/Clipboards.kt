package com.kuva.vpn.util

import android.content.ClipboardManager
import android.content.Context

object Clipboards {
    fun getText(): String? {
        val cm = com.kuva.vpn.KuvaApplication.instance
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return cm.primaryClip?.getItemAt(0)?.text?.toString()
    }
}
