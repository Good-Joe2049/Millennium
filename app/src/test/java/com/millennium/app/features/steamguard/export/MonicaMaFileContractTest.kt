package com.millennium.app.features.steamguard.export

import org.junit.Assert.*
import org.junit.Test

class MonicaMaFileContractTest {
    private val nonce = "a0192068-6fef-428e-942b-eef72639718a"
    private val uri = "content://takagi.ru.monica.steamapp.mafile-handoff/v1/" + "a".repeat(64)

    @Test fun validatesMatchingVersionNonceAndExactUri() {
        assertTrue(MonicaMaFileContract.validResult(1, nonce, nonce, uri))
        assertFalse(MonicaMaFileContract.validResult(2, nonce, nonce, uri))
        assertFalse(MonicaMaFileContract.validResult(1, null, nonce, uri))
        assertFalse(MonicaMaFileContract.validResult(1, "b0192068-6fef-428e-942b-eef72639718a", nonce, uri))
        assertFalse(MonicaMaFileContract.validResult(1, "1-1-1-1-1", "1-1-1-1-1", uri))
    }

    @Test fun rejectsRedirectsUserIdsEncodedPathsAndExtraComponents() {
        for (bad in listOf(null, "", uri.replace("content:", "file:"),
            uri.replace(".mafile-handoff", ".fileprovider"), uri.replace("content://", "content://0@"),
            uri.replace("/v1/", "/v2/"), uri.replace("/v1/", "/%76%31/"),
            "$uri/", "$uri?redirect=1", "$uri#fragment", uri.dropLast(1), uri + "a")) {
            assertFalse("Rejected URI", MonicaMaFileContract.validResult(1, nonce, nonce, bad))
        }
    }
}
