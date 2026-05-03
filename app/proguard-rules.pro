# ── Stacktraces ───────────────────────────────────────────────────────────────
# Сохраняем имена файлов и номера строк для читаемых крэш-репортов
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── Kotlin Coroutines ─────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.coroutines.** {
    volatile <fields>;
}

# ── DataStore ─────────────────────────────────────────────────────────────────
# Preferences DataStore does not require broad keep rules.

# ── GSON / Data Models ────────────────────────────────────────────────────────
# Сохраняем модели данных для корректной десериализации (GSON)
-keep class com.wireturn.app.data.Profile { *; }
-keep class com.wireturn.app.data.ClientConfig { *; }
-keep class com.wireturn.app.data.XraySettings { *; }
-keep class com.wireturn.app.data.XrayConfig { *; }
-keep class com.wireturn.app.data.WgConfig { *; }
-keep class com.wireturn.app.data.VlessConfig { *; }
-keep class com.wireturn.app.data.DCType { *; }
-keep class com.wireturn.app.data.KernelVariant { *; }
-keep class com.wireturn.app.data.XrayConfiguration { *; }

# ── JNI / Native ──────────────────────────────────────────────────────────────
# Сохраняем класс и нативные методы для hev-socks5-tunnel
-keep class com.wireturn.app.HevSocks5Tunnel {
    native <methods>;
}
-keepclasseswithmembernames class * {
    native <methods>;
}

# ── Tink (транзитивная зависимость security-crypto) ───────────────────────────
# Аннотационные библиотеки не включены в runtime, предупреждения безопасно подавить
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.concurrent.GuardedBy
