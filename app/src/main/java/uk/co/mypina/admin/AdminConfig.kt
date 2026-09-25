package uk.co.mypina.admin

import android.net.Uri

/** Where the admin site lives, from BuildConfig.ADMIN_BASE_URL (debug: localhost:3000, release: admin.mypina.co.uk). */
object AdminConfig {
    val baseUrl: String = BuildConfig.ADMIN_BASE_URL.trimEnd('/')
    val startUrl: String = "$baseUrl/admin"
    val adminHost: String = Uri.parse(baseUrl).host.orEmpty()

    /** Extra hosts that count as "the admin site" in debug builds (dev server via adb reverse or emulator). */
    private val debugHosts = setOf("localhost", "10.0.2.2", "admin.localhost")

    fun isAdminHost(host: String?): Boolean {
        if (host == null) return false
        val h = host.lowercase()
        if (h == adminHost.lowercase()) return true
        return BuildConfig.DEBUG && h in debugHosts
    }

    val userAgentSuffix: String = " PinaAdmin/${BuildConfig.VERSION_NAME}"
}
