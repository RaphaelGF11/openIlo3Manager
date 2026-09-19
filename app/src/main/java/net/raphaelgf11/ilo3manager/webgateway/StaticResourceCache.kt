package net.raphaelgf11.ilo3manager.webgateway

/**
 * Caches iLO's static assets (JS, CSS, images, translations) in memory, per gateway.
 *
 * Transfers from this server run at roughly 35 KB/s regardless of how fast the network is — the
 * BMC's own processor is the bottleneck encrypting 3DES, the only cipher available here (Bouncy
 * Castle no longer implements the RC4 suites iLO also offers). The single biggest asset is ~92 KB,
 * so re-fetching the same files on every page view costs seconds that no amount of connection
 * reuse can recover. iLO's static content is fixed per firmware build — it reports the same ETag
 * for every file and a 1950 Last-Modified date — so caching it for the lifetime of the gateway is
 * safe; stopping and restarting the gateway (or the app) clears it.
 *
 * Only successful GETs of asset paths are cached; everything under /json/ is live state and is
 * never cached.
 */
class StaticResourceCache(private val maxBytes: Int = 12 * 1024 * 1024) {

    class Entry(val response: RawHttpResponse)

    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var currentBytes = 0

    @Synchronized
    fun get(key: String): RawHttpResponse? = entries[key]?.response

    @Synchronized
    fun put(key: String, response: RawHttpResponse) {
        val size = response.body.size
        if (size > maxBytes) return
        entries.remove(key)?.let { currentBytes -= it.response.body.size }
        entries[key] = Entry(response)
        currentBytes += size
        // Access-ordered map: the eldest entry is the least recently used.
        val iterator = entries.entries.iterator()
        while (currentBytes > maxBytes && iterator.hasNext()) {
            val eldest = iterator.next()
            currentBytes -= eldest.value.response.body.size
            iterator.remove()
        }
    }

    companion object {
        private val CACHEABLE_PREFIXES = listOf("/js/", "/css/", "/images/", "/lang/", "/html/")

        /**
         * Cache-key for a request, or null if it must always go to the device. Deliberately keyed
         * on the path alone: the UI appends a changing cache-busting query parameter to these
         * fetches, which would otherwise make every lookup a miss even though the bytes are
         * identical.
         */
        fun keyFor(method: String, uri: String): String? {
            if (method != "GET") return null
            if (!CACHEABLE_PREFIXES.any { uri.startsWith(it) }) return null
            return uri
        }
    }
}
