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
# Mandatory rules for GSON to work with obfuscation
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses

# Specific data models used for serialization.
# We keep members to ensure GSON can access fields via reflection
# and that field names match JSON keys where @SerializedName is missing.
-keep class com.wireturn.app.data.Profile { *; }
-keep class com.wireturn.app.data.TurnableConfig { *; }
-keep class com.wireturn.app.data.TurnableRoute { *; }
-keep class com.wireturn.app.data.OlcrtcConfig { *; }
-keep class com.wireturn.app.data.WebdavConfig { *; }
-keep class com.wireturn.app.data.WgConfig { *; }
-keep class com.wireturn.app.data.VlessConfig { *; }
-keep class com.wireturn.app.data.ClientConfig { *; }
-keep class com.wireturn.app.data.XraySettings { *; }
-keep class com.wireturn.app.data.XrayConfig { *; }
-keep class com.wireturn.app.data.VpnSettings { *; }
-keep class com.wireturn.app.data.AutoLaunchSettings { *; }
-keep class com.wireturn.app.data.KernelSnapshot { *; }
-keep class com.wireturn.app.data.OldClientConfig { *; }

# Keep enums and their members
-keepclassmembers enum com.wireturn.app.data.** { *; }

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
