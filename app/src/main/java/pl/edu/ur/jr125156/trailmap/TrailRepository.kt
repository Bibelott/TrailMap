package pl.edu.ur.jr125156.trailmap

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

object TrailRepository {
    private const val CACHED_FILE_NAME = "cached_trails.geojson"

    // 1. Load Data (Prioritize Cache, then Asset)
    fun loadTrails(context: Context): String? {
        val cachedFile = File(context.filesDir, CACHED_FILE_NAME)

        // A. Try loading downloaded file
        if (cachedFile.exists()) {
            try {
                return cachedFile.readText()
            } catch (e: Exception) {
                e.printStackTrace() // Corrupted file? Fallback.
            }
        }

        // B. Fallback to bundled Asset
        return try {
            context.assets.open("trails.geojson").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }

    // 2. Download & Save (Updates the cache)
    suspend fun downloadTrails(context: Context, url: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                // Download
                val jsonString = URL(url).readText()

                // Save to internal storage
                val file = File(context.filesDir, CACHED_FILE_NAME)
                file.writeText(jsonString)

                jsonString // Return new data to update UI
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }
}
