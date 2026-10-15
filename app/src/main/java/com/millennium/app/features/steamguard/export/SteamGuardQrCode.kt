package com.millennium.app.features.steamguard.export

import android.net.Uri
import androidx.core.net.toUri
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.millennium.app.features.steamguard.data.SteamGuardAccount

internal enum class SteamGuardQrFormat(
    val label: String,
    private val prefix: String,
    val packageName: String,
) {
    Stratum("Stratum", "otpauth://totp/Steam:", "com.stratumauth.app"),
    Aegis("Aegis", "otpauth://steam/", "com.beemdevelopment.aegis"),
    Monica("Monica", "otpauth://totp/Steam:", "takagi.ru.monica");

    fun createUri(account: SteamGuardAccount): Uri {
        require(account.username.isNotBlank() && account.secret.isNotBlank())
        return (
                "$prefix${Uri.encode(account.username)}" +
                        "?secret=${Uri.encode(account.secret)}&issuer=Steam"
                ).toUri()
    }
}

internal data class SteamGuardQrPair(val stratum: BitMatrix, val aegis: BitMatrix)

internal object SteamGuardQrCode {
    fun createPair(account: SteamGuardAccount): SteamGuardQrPair {
        // Monica uses the same URI and QR code as Stratum; only two matrices are needed.
        val uris = listOf(SteamGuardQrFormat.Stratum, SteamGuardQrFormat.Aegis)
            .map { it.createUri(account).toString() }
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
