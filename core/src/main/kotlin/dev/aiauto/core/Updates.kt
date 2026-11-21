package dev.aiauto.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

@Serializable
data class Release(
    @SerialName("tag_name") val tag: String,
    @SerialName("html_url") val htmlUrl: String,
)

/** Checks this app's GitHub releases for a newer version. */
object Updates {
    const val REPO = "mjparinas/aiauto"
    private val json = Json { ignoreUnknownKeys = true }

    /** The latest published release. Public repo, so no token is needed. */
    fun latest(http: OkHttpClient = OkHttpClient()): Release {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 404) throw IOException("No releases published yet.")
            if (!response.isSuccessful) throw IOException("Couldn't check for updates (HTTP ${response.code}).")
            return json.decodeFromString(response.body?.string().orEmpty())
        }
    }

    /** True when [latest] is a higher version than [current]. Accepts tags like "v0.2.0". */
    fun isNewer(latest: String, current: String): Boolean {
        val a = parts(latest)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parts(version: String): List<Int> =
        version.trim().removePrefix("v").removePrefix("V").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
}
