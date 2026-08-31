package com.fsaint.androidagent

import com.fsaint.androidagent.skills.SkillArchiveDownloader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

class UrlConnectionSkillArchiveDownloader(
    private val maxBytes: Int = 2 * 1024 * 1024,
) : SkillArchiveDownloader {
    override fun download(url: String): ByteArray {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = false
        }
        try {
            val status = connection.responseCode
            require(status in 200..299) { "Skill download failed" }
            require(connection.contentLengthLong <= maxBytes || connection.contentLengthLong < 0)
            return connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (out.size() + count > maxBytes) error("Skill archive too large")
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
        } finally { connection.disconnect() }
    }
}
