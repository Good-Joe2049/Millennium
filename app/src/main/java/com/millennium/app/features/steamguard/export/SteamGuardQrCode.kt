package com.millennium.app.features.steamguard.export

import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.millennium.app.features.steamguard.data.SteamGuardAccount

internal enum class SteamGuardQrFormat(val label: String, val prefix: String) {
    Stratum("Stratum", "otpauth://totp/Steam:"),
    Aegis("Aegis", "otpauth://steam/"),
}

internal data class SteamGuardQrPair(val stratum: BitMatrix, val aegis: BitMatrix)

internal object SteamGuardQrCode {
    fun createPair(account: SteamGuardAccount): SteamGuardQrPair {
        require(account.username.isNotBlank() && account.secret.isNotBlank())
        val uris = SteamGuardQrFormat.entries.map { format ->
            "${format.prefix}${Uri.encode(account.username)}" +
                    "?secret=${Uri.encode(account.secret)}&issuer=Steam"
        }
        val writer = QRCodeWriter()
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 0,
        )
        // Logical modules, not a bitmap to enlarge: the renderer draws integer-pixel cells.
        val matrices = uris.map { writer.encode(it, BarcodeFormat.QR_CODE, 0, 0, hints) }
        // Match the demo's version 6 grid (41x41), growing both codes if either URL needs it.
        val version = maxOf(6, (matrices.maxOf { it.width } - 17) / 4)
        val matched = matrices.mapIndexed { index, matrix ->
            if (matrix.width == 17 + 4 * version) matrix
            else writer.encode(
                uris[index], BarcodeFormat.QR_CODE, 0, 0,
                hints + (EncodeHintType.QR_VERSION to version),
            )
        }
        return SteamGuardQrPair(matched[0], matched[1])
    }
}
