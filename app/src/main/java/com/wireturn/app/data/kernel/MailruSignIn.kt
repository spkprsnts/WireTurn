package com.wireturn.app.data.kernel

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The device's Mail.ru sign-in for OpenFlux's mailru carrier. Since 0.14.2 Cloud opens a public
 * document (r7/edit) only for a signed-in account ("public is available with auth only", 401), and
 * since 0.14.3 an exit no longer shares its own sign-in with a client: every device signs in itself.
 * One sign-in serves every Mail.ru profile - it's kept here and copied into the core's cookie store
 * under the profile's document link at each start (see [prepareCookieStore]).
 *
 * It has to be another account than the exit's. Seen live (core 0.17.1 client, node-v1.7.3 exit,
 * one account on both): the exit's writes went through, the client's chat writes were cut with
 * disconnectReason 4004 ("access deny") after ~30 messages and never reached the exit - the R7
 * message budget looks shared per account, not per connection. Two accounts worked at once.
 */
object MailruSignIn {
    /** The cookie that marks a signed-in account (the core's manager/signin.go loginCookie). */
    const val LOGIN_COOKIE = "Mpop"

    /** Where the carrier's own requests go (mailru.go's ApplyCookies/FetchCookies URL). */
    const val COOKIE_URL = "https://cloud.mail.ru/"

    private const val SIGN_IN_FILE = "mailru_signin.json"

    // mailru.go's mailruUserAgent: Cloud may tie what it answers to the agent, so the check below
    // asks as the carrier will.
    private const val CARRIER_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:155.0) Gecko/20100101 Firefox/155.0"

    // The core's own default for the mailru store (main.go's defaultCookieStorePath, in its working
    // directory - filesDir), passed explicitly with --cookie-store all the same.
    private const val STORE_FILE = "cookies-mailru.json"

    private val gson = Gson()
    private val jarType = object : TypeToken<Map<String, String>>() {}.type
    private val storeType = object : TypeToken<Map<String, Map<String, String>>>() {}.type

    fun storeFile(filesDir: File) = File(filesDir, STORE_FILE)

    fun isSignedIn(filesDir: File) = File(filesDir, SIGN_IN_FILE).isFile

    /**
     * A WebView Cookie header ("a=1; b=2") as a jar, the way provision.CookieStore reads one -
     * null unless it holds a signed-in account.
     */
    fun parseHeader(header: String?): Map<String, String>? {
        val jar = header.orEmpty().split(';').mapNotNull { part ->
            val name = part.substringBefore('=', "").trim()
            val value = part.substringAfter('=', "").trim()
            if (name.isEmpty() || '\r' in name + value || '\n' in name + value) null else name to value
        }.toMap()
        return jar.takeIf { !it[LOGIN_COOKIE].isNullOrEmpty() }
    }

    /**
     * Whether Cloud takes [jar] as a session: the CSRF token call the carrier makes before it opens
     * a document (mailru.go's csrfToken), with its User-Agent. Mpop alone isn't enough - it shows
     * up partway through the sign-in's redirects, before the cookies Cloud checks (ssdc, p, ...),
     * and a jar taken then gets 403 from r7/edit. Blocking network call.
     */
    fun verify(jar: Map<String, String>): Boolean = runCatching {
        val connection = URL("https://cloud.mail.ru/api/v2/tokens/csrf").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Cookie", jar.entries.joinToString("; ") { "${it.key}=${it.value}" })
            connection.setRequestProperty("User-Agent", CARRIER_USER_AGENT)
            connection.setRequestProperty("Accept", "application/json, text/plain, */*")
            connection.setRequestProperty("Referer", "https://cloud.mail.ru/home/")
            connection.responseCode == 200 &&
                connection.inputStream.bufferedReader().use { it.readText() }.let { body ->
                    JsonParser.parseString(body).asJsonObject.getAsJsonObject("body")
                        ?.get("token")?.asString.orEmpty().isNotBlank()
                }
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)

    fun save(filesDir: File, jar: Map<String, String>) {
        writeAtomically(File(filesDir, SIGN_IN_FILE), gson.toJson(jar))
    }

    /** Drops the sign-in, and the core's store with it - a running core persists what it holds there. */
    fun clear(filesDir: File) {
        File(filesDir, SIGN_IN_FILE).delete()
        storeFile(filesDir).delete()
    }

    /**
     * Puts the sign-in into the core's store under [docUrl] - the key the core looks a carrier's
     * jar up by (sessionCookieKey: the --url as given). What the store already holds for it (the
     * exit's shareable cookies) stays, the sign-in's own cookies win. Without a sign-in the store
     * goes: one left from before a sign-out would otherwise still sign the core in.
     */
    fun prepareCookieStore(filesDir: File, docUrl: String) {
        val store = storeFile(filesDir)
        val jar = runCatching {
            gson.fromJson<Map<String, String>>(File(filesDir, SIGN_IN_FILE).readText(), jarType)
        }.getOrNull()
        if (jar.isNullOrEmpty()) {
            store.delete()
            return
        }
        val current = runCatching {
            gson.fromJson<Map<String, Map<String, String>>>(store.readText(), storeType)
        }.getOrNull().orEmpty()
        writeAtomically(store, gson.toJson(current + (docUrl to current[docUrl].orEmpty() + jar)))
    }

    private fun writeAtomically(file: File, text: String) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
