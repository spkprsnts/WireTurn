package com.wireturn.app.domain

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

object ProfileEncoder {
    fun encode(json: String): String {
        val input = json.toByteArray(Charsets.UTF_8)
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(input)
        deflater.finish()

        // A single fixed-size deflate() call can't guarantee the whole input fits - loop until
        // finished() instead, growing the output as needed (matches decode()'s inflate loop below).
        val bos = ByteArrayOutputStream(input.size)
        val buf = ByteArray(1024)
        while (!deflater.finished()) {
            val count = deflater.deflate(buf)
            if (count == 0) break
            bos.write(buf, 0, count)
        }
        deflater.end()

        return Base64.encodeToString(bos.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun decode(encoded: String): String? {
        return try {
            val compressed = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val inflater = Inflater()
            inflater.setInput(compressed)
            val bos = ByteArrayOutputStream(compressed.size)
            val buf = ByteArray(1024)
            while (!inflater.finished()) {
                val count = inflater.inflate(buf)
                if (count == 0) break
                bos.write(buf, 0, count)
            }
            inflater.end()
            bos.toString("UTF-8")
        } catch (e: Exception) {
            null
        }
    }
}
