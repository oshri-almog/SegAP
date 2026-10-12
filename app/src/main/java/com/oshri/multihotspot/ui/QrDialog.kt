package com.oshri.multihotspot.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Standard Wi-Fi join payload understood by Android and iOS camera apps. */
fun wifiQrPayload(ssid: String, password: String, hidden: Boolean): String {
    fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:").replace("\"", "\\\"")
    return "WIFI:T:WPA;S:${esc(ssid)};P:${esc(password)};" + (if (hidden) "H:true;" else "") + ";"
}

fun qrBitmap(content: String, sizePx: Int = 720): Bitmap {
    val hints = mapOf(
        EncodeHintType.MARGIN to 1,
        EncodeHintType.CHARACTER_SET to "UTF-8",
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
    )
    val m = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val pixels = IntArray(m.width * m.height) { i ->
        if (m.get(i % m.width, i / m.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    return Bitmap.createBitmap(pixels, m.width, m.height, Bitmap.Config.ARGB_8888)
}

@Composable
fun WifiQrDialog(ssid: String, networkName: String, password: String, hidden: Boolean, onDismiss: () -> Unit) {
    val bitmap = remember(ssid, password, hidden) { qrBitmap(wifiQrPayload(ssid, password, hidden)).asImageBitmap() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Join $networkName") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Always black-on-white with a quiet zone, so cameras read it in dark mode too.
                Box(Modifier.background(Color.White, RoundedCornerShape(12.dp)).padding(12.dp)) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "QR code to join $ssid as $networkName",
                        filterQuality = FilterQuality.None,
                        modifier = Modifier.size(240.dp),
                    )
                }
                Text(
                    "Scan with the phone's camera to join \"$ssid\". The device lands in $networkName.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
