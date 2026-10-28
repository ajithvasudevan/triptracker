package dev.ajithv.triptracker

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request

/** Decoded-bitmap cache + preloader. Mirrors the web app's imgCache/preloadUrl/setImg.
 *  A single in-flight fetch per URL is shared (Deferred map) so `show()` and `preload()` racing on the
 *  same URL never strand a view as permanent black — `show()` awaits the shared fetch rather than giving up. */
class PhotoRepo(private val api: Api) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 2).toInt()   // ~½ heap (largeHeap on); previews are big
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val pending = HashMap<String, Deferred<Bitmap?>>()
    private val lock = Mutex()

    fun cached(url: String): Bitmap? = cache.get(url)

    private fun decode(bytes: ByteArray, maxDim: Int): Bitmap? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
        var sample = 1
        val longest = maxOf(b.outWidth, b.outHeight)
        while (longest > 0 && longest / sample > maxDim) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private suspend fun httpBitmap(url: String, maxDim: Int): Bitmap? = withContext(Dispatchers.IO) {
        repeat(2) { attempt ->
            try {
                val req = Request.Builder().url(url).header("X-TT-Token", api.token()).get().build()
                api.client.newCall(req).execute().use { r ->
                    if (r.isSuccessful) {
                        val bytes = r.body?.bytes()
                        if (bytes != null) { val bm = decode(bytes, maxDim); if (bm != null) return@withContext bm }
                    }
                }
            } catch (e: Exception) { /* retry once */ }
        }
        null
    }

    /** Fetch (or join the in-flight fetch for) a URL, caching the result. */
    private suspend fun getOrFetch(url: String, maxDim: Int): Bitmap? {
        cache.get(url)?.let { return it }
        val def: Deferred<Bitmap?> = lock.withLock {
            pending[url] ?: scope.async(Dispatchers.IO) {
                val bm = httpBitmap(url, maxDim); if (bm != null) cache.put(url, bm); bm
            }.also { pending[url] = it }
        }
        return try { def.await() } finally { lock.withLock { if (pending[url] === def) pending.remove(url) } }
    }

    fun preload(url: String, maxDim: Int = 2048) {
        if (cache.get(url) != null) return
        scope.launch { runCatching { getOrFetch(url, maxDim) } }
    }

    /** Show `url` in `img`; a data-url guard means a slow image can't pop in after you've navigated away. */
    fun show(img: ImageView, url: String, maxDim: Int = 2048) {
        img.setTag(R.id.tag_url, url)
        val hit = cache.get(url)
        if (hit != null) { img.setImageBitmap(hit); return }
        img.setImageDrawable(null)  // black placeholder, never the previous photo
        scope.launch {
            val bmp = runCatching { getOrFetch(url, maxDim) }.getOrNull()
            if (bmp != null && img.getTag(R.id.tag_url) == url) img.setImageBitmap(bmp)
        }
    }
}
