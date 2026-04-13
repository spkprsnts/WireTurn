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

# ── Tink (транзитивная зависимость security-crypto) ───────────────────────────
# Аннотационные библиотеки не включены в runtime, предупреждения безопасно подавить
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.concurrent.GuardedBy
