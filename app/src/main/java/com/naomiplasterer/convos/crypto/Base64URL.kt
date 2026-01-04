package com.naomiplasterer.convos.crypto

import android.util.Base64

object Base64URL {
    fun encode(data: ByteArray): String {
        return Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun decode(encoded: String): ByteArray {
        return Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
