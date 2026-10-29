package com.wireturn.app.data.kernel

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter

/** Applies to every Gson reader, including saved profiles, backups and kernel snapshots. */
class OpenFluxConfigAdapter : TypeAdapterFactory {
    override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
        if (type.rawType != OpenFluxConfig::class.java) return null
        val delegate = gson.getDelegateAdapter(this, type)
        return object : TypeAdapter<T>() {
            override fun write(out: JsonWriter, value: T) = delegate.write(out, value)

            override fun read(reader: JsonReader): T {
                val json = JsonParser.parseReader(reader)
                val value = delegate.fromJsonTree(json)
                if (value !is OpenFluxConfig) return value
                val version = json.asJsonObject.get("context_version")
                    ?.takeUnless { it.isJsonNull }?.asInt ?: 0
                if (version >= 1) return value
                val old = value.sanitize()
                // Older WireTurn dropped contexts equal to the old default. Pin that default
                // before switching the core, so a node-v1.6.0 peer sees the same keys immediately.
                val migrated = old.copy(
                    sessionContext = old.sessionContext.ifBlank {
                        if (old.encryptionKey.isNotBlank()) OpenFluxConfig.legacyContext(old.transport, old.url) else ""
                    },
                    contextVersion = 1
                )
                @Suppress("UNCHECKED_CAST")
                return migrated as T
            }
        }.nullSafe()
    }
}
