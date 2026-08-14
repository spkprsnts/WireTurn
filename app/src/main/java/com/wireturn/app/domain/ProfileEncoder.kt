package com.wireturn.app.domain

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

object ProfileEncoder {
    fun encode(json: String): String {
        val input = json.toByteArray(Charsets.UTF_8)
        val output = ByteArray(input.size + 100)
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(input)
        deflater.finish()
        val compressedSize = deflater.deflate(output)
        deflater.end()
        
        val compressed = output.copyOfRange(0, compressedSize)
        return Base64.encodeToString(compressed, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
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
