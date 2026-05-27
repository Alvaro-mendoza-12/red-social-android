package conectate.com.presentation.voicerooms

import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * CinemaCatalog - Professional Content & Premium Resolver Engine.
 * v9.5 Architecture: Meta -> Scraper -> RealDebrid -> ExoPlayer.
 */
class CinemaCatalog(
    private val tmdbApiKey: String,
    private val realDebridToken: String = ""
) {
    private val client = OkHttpClient()
    private val hasTmdbKey: Boolean
        get() = tmdbApiKey.isNotBlank()
    
    init {
        if (realDebridToken.isNotEmpty()) {
            Log.i("CinemaCatalog", "Real-Debrid resolver configurado")
        }
    }

    suspend fun searchMovies(query: String, isTv: Boolean = false): List<ContentItem> = withContext(Dispatchers.IO) {
        if (!hasTmdbKey) return@withContext emptyList()
        try {
            val type = if (isTv) "tv" else "movie"
            val url = "https://api.themoviedb.org/3/search/$type?api_key=$tmdbApiKey&query=$query&language=es-ES"
            fetchFromTmdb(url, isTv = isTv)
        } catch (e: Exception) {
            Log.e("CinemaCatalog", "TMDB Search error: ${e.message}")
            emptyList()
        }
    }

    suspend fun searchAnime(query: String): List<ContentItem> = withContext(Dispatchers.IO) {
        try {
            val url = "https://graphql.anilist.co"
            val queryStr = """
                query (${'$'}query: String) {
                  Page(perPage: 10) {
                    media(search: ${'$'}query, type: ANIME) {
                      title { romaji english }
                      coverImage { large }
                    }
                  }
                }
            """.trimIndent()
            val jsonBody = JSONObject().apply {
                put("query", queryStr)
                put("variables", JSONObject().apply { put("query", query) })
            }
            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            val requestBody = jsonBody.toString().toRequestBody(mediaType)
            
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0")
                .post(requestBody)
                .build()
            val response = client.newCall(request).execute()
            val json = JSONObject(response.body?.string() ?: "{}")
            val media = json.getJSONObject("data").getJSONObject("Page").getJSONArray("media")
            val items = mutableListOf<ContentItem>()
            for (i in 0 until media.length()) {
                val obj = media.getJSONObject(i)
                val titles = obj.getJSONObject("title")
                val englishTitle = titles.optString("english")
                val title = englishTitle.takeIf { it != "null" && it.isNotEmpty() } ?: titles.getString("romaji")
                items.add(ContentItem(
                    title = title,
                    posterUrl = obj.getJSONObject("coverImage").getString("large"),
                    provider = "anilist",
                    providerId = title
                ))
            }
            items
        } catch (e: Exception) { emptyList() }
    }

    private suspend fun getTrendingAnimeFromAnilist(): List<ContentItem> = withContext(Dispatchers.IO) {
        try {
            val queryStr = """
                query {
                  Page(perPage: 15) {
                    media(type: ANIME, sort: TRENDING_DESC) {
                      title { romaji english }
                      coverImage { large }
                    }
                  }
                }
            """.trimIndent()
            val jsonBody = JSONObject().apply { put("query", queryStr) }
            val requestBody = jsonBody.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

            val request = Request.Builder()
                .url("https://graphql.anilist.co")
                .addHeader("User-Agent", "Conectate Android")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val json = JSONObject(response.body?.string() ?: "{}")
            val media = json.getJSONObject("data").getJSONObject("Page").getJSONArray("media")
            val items = mutableListOf<ContentItem>()
            for (i in 0 until media.length()) {
                val obj = media.getJSONObject(i)
                val titles = obj.getJSONObject("title")
                val title = titles.optString("english")
                    .takeIf { it != "null" && it.isNotEmpty() }
                    ?: titles.getString("romaji")
                items.add(
                    ContentItem(
                        title = title,
                        posterUrl = obj.getJSONObject("coverImage").getString("large"),
                        provider = "anilist",
                        providerId = title
                    )
                )
            }
            items
        } catch (e: Exception) {
            Log.e("CinemaCatalog", "AniList trending error: ${e.message}")
            emptyList()
        }
    }

    /**
     * Consumet Anime Search - The "Secret Sauce" for Anime.
     */
    /**
     * Consumet Anime Search - The "Secret Sauce" for Anime.
     * Uses GogoAnime directly for better source matching.
     */
    suspend fun searchAnimeConsumet(query: String): List<ContentItem> = withContext(Dispatchers.IO) {
        searchAnime(query)
    }

    /**
     * Consumet Anime Search - The "Secret Sauce" for Anime.
     */
    suspend fun searchUniversal(query: String, type: String = "all"): List<ContentItem> = withContext(Dispatchers.IO) {
        val allResults = mutableListOf<ContentItem>()
        try {
            when (type) {
                // v10.0: Proper isolation of categories
                // v16.2: Search returns ALL results to avoid hiding popular items, filtering is for Trending only.
                "anime" -> return@withContext searchAnimeConsumet(query)
                "movie" -> return@withContext searchMovies(query, isTv = false)
                "tv" -> return@withContext searchMovies(query, isTv = true)
                "drama" -> return@withContext searchDramas(query)
                else -> {
                    allResults.addAll(searchAnime(query))
                    if (hasTmdbKey) allResults.addAll(searchMovies(query))
                    allResults.addAll(searchPublicDomain(query))
                }
            }
        } catch (e: Exception) {
            Log.e("CinemaCatalog", "Universal Search error: ${e.message}")
        }
        allResults.distinctBy { it.title.lowercase().trim() }
    }

    private suspend fun searchDramas(query: String): List<ContentItem> = withContext(Dispatchers.IO) {
        if (!hasTmdbKey) return@withContext emptyList()
        // Using TMDB with filter for Korean content for K-Dramas
        val url = "https://api.themoviedb.org/3/search/tv?api_key=$tmdbApiKey&query=$query&with_original_language=ko&language=es-ES"
        fetchFromTmdb(url, isTv = true, providerOverride = "drama")
    }

    suspend fun getAnimeEpisodes(animeId: String): List<Episode> = withContext(Dispatchers.IO) {
        try {
            // v15.9: GogoAnime Info Endpoint
            val url = "https://api.consumet.org/anime/gogoanime/info/$animeId"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val json = JSONObject(response.body?.string() ?: "{}")
            
            // Validate: Gogo API puts episodes in "episodes" array
            val episodesArray = json.optJSONArray("episodes") ?: return@withContext emptyList()
            
            val episodes = mutableListOf<Episode>()
            for (i in 0 until episodesArray.length()) {
                val obj = episodesArray.getJSONObject(i)
                episodes.add(Episode(
                    number = obj.optInt("number", i + 1),
                    title = "Episodio ${obj.optInt("number", i + 1)}",
                    videoUrl = obj.getString("id") // Ensure this ID is used for streaming
                ))
            }
            episodes
        } catch (e: Exception) {
            Log.e("CinemaCatalog", "GetAnimeEpisodes error: ${e.message}")
            emptyList()
        }
    }

    /**
     * Archive.org Search - The source of legal, free, public domain movies.
     */
    suspend fun searchPublicDomain(query: String): List<ContentItem> = withContext(Dispatchers.IO) {
        try {
            // Search for movies in public domain collections
            val url = "https://archive.org/advancedsearch.php?q=title:($query)+AND+mediatype:(movies)+AND+collection:(feature_films+OR+scifi+OR+classic_cartoons)&fl[]=identifier&fl[]=title&fl[]=description&rows=10&output=json"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val json = JSONObject(response.body?.string() ?: "{}")
            val results = json.getJSONObject("response").getJSONArray("docs")
            
            val items = mutableListOf<ContentItem>()
            for (i in 0 until results.length()) {
                val obj = results.getJSONObject(i)
                val id = obj.getString("identifier")
                
                // Archive.org logic: usually identifier/identifier.mp4 (simplified for MVP)
                val directUrl = "https://archive.org/download/$id/$id.mp4"
                
                items.add(ContentItem(
                    title = obj.getString("title"),
                    posterUrl = "https://archive.org/services/img/$id",
                    videoUrl = directUrl,
                    description = obj.optString("description", "Obra de dominio público."),
                    provider = "archive",
                    providerId = id
                ))
            }
            items
        } catch (e: Exception) {
            Log.e("CinemaCatalog", "Archive.org search error: ${e.message}")
            emptyList()
        }
    }

    /**
     * Resolver Engine - Attempts to find a direct video link for a title.
     * v7.6: Uses public community endpoints and heuristics.
     */
    /**
     * Resolver Engine - Finds a PLAYABLE link for a specific item.
     * v7.7: Supports Archive Metadata, AniList Search and Vidsrc Heuristics.
     */
    suspend fun resolveStream(item: ContentItem): String? = withContext(Dispatchers.IO) {
        try {
            when (item.provider) {
                "archive" -> {
                    val url = "https://archive.org/metadata/${item.providerId}/files"
                    val request = Request.Builder().url(url).build()
                    val response = client.newCall(request).execute()
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val files = json.getJSONArray("files")
                    
                    var bestFile: String? = null
                    for (i in 0 until files.length()) {
                        val fileObj = files.getJSONObject(i)
                        val name = fileObj.getString("name")
                        val format = fileObj.optString("format", "")
                        
                        if (name.endsWith(".mp4", ignoreCase = true)) {
                            if (format.contains("h.264", ignoreCase = true) || format.contains("MP4", ignoreCase = true)) {
                                bestFile = name
                                break 
                            }
                            bestFile = name 
                        }
                    }
                    if (bestFile != null) return@withContext "https://archive.org/download/${item.providerId}/$bestFile"
                }
                "anilist", "tmdb", "drama", "tmdb_tv" -> {
                    Log.d("CinemaCatalog", "🎬 Resolviendo: ${item.title}")

                    // v15.8 Strategy 1: Real-Debrid Premium (EXOPLAYER - AD FREE)
                    if (realDebridToken.isNotEmpty()) {
                        Log.d("CinemaCatalog", "💎 Probando Real-Debrid...")
                        val rdLink = resolvePremiumStream(item)
                        if (rdLink != null) {
                            Log.d("CinemaCatalog", "✅ Real-Debrid exitoso!")
                            return@withContext rdLink
                        }
                        Log.w("CinemaCatalog", "❌ Real-Debrid no encontró enlaces")
                    }

                    // v15.8 Strategy 2: Direct Anime Extractor (EXOPLAYER - AD FREE)
                    if (item.provider == "consumet") {
                        Log.d("CinemaCatalog", "🎌 Probando Extractor Anime Directo...")
                        val directLink = resolveAnimeLink(item.providerId)
                        if (directLink != null) {
                            Log.d("CinemaCatalog", "✅ Anime Directo disponible")
                            return@withContext directLink
                        }
                    }

                    Log.w("CinemaCatalog", "No playable stream configured for metadata item: ${item.title}")
                    return@withContext null
                }
                "consumet" -> {
                    // Handled via resolveAnimeLink during episode selection
                    return@withContext null 
                }
            }
            Log.e("CinemaCatalog", "❌ Todo falló para: ${item.title}")
            null
        } catch (e: Exception) {
            Log.e("CinemaCatalog", "Resolution error for ${item.title}: ${e.message}")
            null
        }
    }

    suspend fun resolveAnimeLink(episodeId: String): String? = withContext(Dispatchers.IO) {
        try {
            // v15.9: GogoAnime Watch Endpoint (Same logic as resolveAnimeStream)
            val url = "https://api.consumet.org/anime/gogoanime/watch/$episodeId"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: "{}"
            val json = JSONObject(body)
            val sources = json.optJSONArray("sources") ?: return@withContext null
            
            // Priority: Default -> Backup -> M3U8
            var bestLink: String? = null
            
            for (i in 0 until sources.length()) {
                val src = sources.getJSONObject(i)
                val quality = src.optString("quality", "")
                val link = src.optString("url")
                
                if (quality == "default" && link.isNotEmpty()) return@withContext link
                if (quality == "backup" && link.isNotEmpty()) bestLink = link
                if (bestLink == null && link.isNotEmpty()) bestLink = link
            }
            
            // Fallback to searching specifically for m3u8 if not found above
            if (bestLink == null) {
                for (i in 0 until sources.length()) {
                    val streamUrl = sources.getJSONObject(i).getString("url")
                    if (streamUrl.contains(".m3u8")) return@withContext streamUrl
                }
            }
            
            bestLink
        } catch (e: Exception) {
            Log.e("CinemaCatalog", "AnimeLink resolve error: ${e.message}")
            null
        }
    }

    /**
     * v14.0: Enhanced Trending with multiple categories for proper filtering
     */
     /**
     * v14.0: Enhanced Trending with multiple categories for proper filtering
     * v15.0: Static Cache implementation for instance loading (<2s)
     */
    suspend fun getTrending(): Map<String, List<ContentItem>> = withContext(Dispatchers.IO) {
        // Cache Check
        val currentTime = System.currentTimeMillis()
        val cached = cachedTrending
        if (cached != null && (currentTime - lastCacheTime < CACHE_DURATION)) {
            Log.d("CinemaCatalog", "Usando caché en memoria (${cached.size} categorías)")
            return@withContext cached
        }

        val trendingMap = mutableMapOf<String, List<ContentItem>>()
        try {
            if (hasTmdbKey) {
                val movieUrl = "https://api.themoviedb.org/3/trending/movie/week?api_key=$tmdbApiKey&language=es-ES"
                trendingMap["Películas Populares 🎬"] = fetchFromTmdb(movieUrl, isTv = false)
                
                val tvUrl = "https://api.themoviedb.org/3/trending/tv/week?api_key=$tmdbApiKey&language=es-ES"
                trendingMap["Series Destacadas 📺"] = fetchFromTmdb(tvUrl, isTv = true)

                val dramaUrl = "https://api.themoviedb.org/3/discover/tv?api_key=$tmdbApiKey&with_original_language=ko&sort_by=popularity.desc&language=es-ES"
                trendingMap["K-Dramas 🌸"] = fetchFromTmdb(dramaUrl, isTv = true)
            }
            
            val animeList = getTrendingAnimeFromAnilist()
            if (animeList.isNotEmpty()) trendingMap["Anime Popular 🔥"] = animeList
            
            // v14.0: Cine Clásico (Archive.org)
            try {
                val classicList = listOf(
                   ContentItem(
                       title = "La Noche de los Muertos Vivientes", 
                       posterUrl = "https://archive.org/download/night_of_the_living_dead/__ia_thumb.jpg", 
                       videoUrl = "https://archive.org/download/night_of_the_living_dead/night_of_the_living_dead_512kb.mp4", 
                       provider = "archive", 
                       providerId = "night_of_the_living_dead",
                       description = "Cine Clásico (1968)"
                   ),
                   ContentItem(
                       title = "Charada (Audrey Hepburn)", 
                       posterUrl = "https://archive.org/download/Charade1963/__ia_thumb.jpg", 
                       videoUrl = "https://archive.org/download/Charade1963/Charade1963_512kb.mp4", 
                       provider = "archive", 
                       providerId = "Charade1963",
                       description = "Cine Clásico (1963)"
                   ),
                   ContentItem(
                       title = "El Maquinista de la General", 
                       posterUrl = "https://archive.org/download/TheGeneral_107/__ia_thumb.jpg", 
                       videoUrl = "https://archive.org/download/TheGeneral_107/TheGeneral_512kb.mp4", 
                       provider = "archive", 
                       providerId = "TheGeneral_107",
                       description = "Cine Clásico (1926)"
                   )
                )
                trendingMap["Cine Clásico 🎞️"] = classicList
            } catch (e: Exception) {}

            // Update Cache
            cachedTrending = trendingMap
            lastCacheTime = System.currentTimeMillis()
            Log.d("CinemaCatalog", "✅ Catálogo actualizado y cacheado")

        } catch (e: Exception) {
            Log.e("CinemaCatalog", "Error fetching trending: ${e.message}")
        }
        trendingMap
    }

    companion object {
        private var cachedTrending: Map<String, List<ContentItem>>? = null
        private var lastCacheTime: Long = 0
        private const val CACHE_DURATION = 1000 * 60 * 30 // 30 minutes
    }


    /**
     * Helper: Convert TMDB ID to IMDB ID for high-precision scraping.
     */
    private suspend fun getImdbId(tmdbId: String, isTv: Boolean): String? = withContext(Dispatchers.IO) {
        try {
            val type = if (isTv) "tv" else "movie"
            val url = "https://api.themoviedb.org/3/$type/$tmdbId/external_ids?api_key=$tmdbApiKey"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val json = JSONObject(response.body?.string() ?: "{}")
            return@withContext json.optString("imdb_id").takeIf { it != "null" && it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Real-Debrid Engine: Scrapes torrents and unrestricts them into direct links.
     * v13.0: Robust Polling Implementation (15s max wait)
     */
    private suspend fun resolvePremiumStream(item: ContentItem): String? = withContext(Dispatchers.IO) {
        if (realDebridToken.isEmpty()) return@withContext null
        try {
            val isTv = (item.provider == "tmdb_tv" || item.provider == "drama")
            val imdbId = getImdbId(item.providerId, isTv)
            
            // v23.5: Hybrid ID/Title Scraper for 100% resolution success
            var results: org.json.JSONArray? = null
            if (imdbId != null) {
                val scraperPath = if (isTv) "series/$imdbId:1:1" else "movie/$imdbId"
                val scraperUrl = "https://torrentio.strem.fun/stream/$scraperPath.json"
                val response = client.newCall(Request.Builder().url(scraperUrl).build()).execute()
                val json = JSONObject(response.body?.string() ?: "{}")
                results = json.optJSONArray("streams")
            }

            if (results == null || results.length() == 0) {
                Log.w("CinemaCatalog", "Torrentio: No results for ID. Trying title search: ${item.title}")
                val cleanTitle = item.title.replace(Regex("[^a-zA-Z0-9 ]"), "").replace(" ", "+")
                val queryPath = if (isTv) "series/$cleanTitle:1:1" else "movie/$cleanTitle"
                val url = "https://torrentio.strem.fun/stream/$queryPath.json"
                val response = client.newCall(Request.Builder().url(url).build()).execute()
                val json = JSONObject(response.body?.string() ?: "{}")
                val fbStreams = json.optJSONArray("streams") ?: return@withContext null
                return@withContext processStreams(fbStreams)
            } else {
                return@withContext processStreams(results)
            }
        } catch (e: Exception) {
            Log.e("CinemaCatalog", "RD Resolution error: ${e.message}")
            null
        }
    }

    private suspend fun processStreams(streams: org.json.JSONArray): String? = withContext(Dispatchers.IO) {
        try {
            Log.d("CinemaCatalog", "🔍 RD: Procesando ${streams.length()} streams de Torrentio")
            val candidates = mutableListOf<JSONObject>()
            for (i in 0 until streams.length()) { 
                val stream = streams.getJSONObject(i)
                Log.d("CinemaCatalog", "   Stream $i: ${stream.optString("title")}")
                candidates.add(stream) 
            }
            
            val sortedStreams = candidates.sortedBy { s -> 
                val title = s.optString("title").lowercase()
                when {
                    title.contains("latino") || title.contains("español") || title.contains("castellano") -> 0
                    title.contains("multi") || title.contains("spanish") || title.contains("subs") -> 1
                    else -> 2
                }
            }
            Log.d("CinemaCatalog", "📊 RD: Streams ordenados por idioma. Intentando top 3...")

            if (sortedStreams.isNotEmpty()) {
                for (i in 0 until minOf(sortedStreams.size, 3)) {
                    val stream = sortedStreams[i]
                    val infoHash = stream.optString("infoHash")
                    if (infoHash.isNullOrEmpty()) continue

                    Log.d("CinemaCatalog", "RD: Processing magnet ${i+1}/3 - ${stream.optString("title")}")
                    
                    val addUrl = "https://api.real-debrid.com/rest/1.0/torrents/addMagnet"
                    val formBody = okhttp3.FormBody.Builder().add("magnet", "magnet:?xt=urn:btih:$infoHash").build()
                    val addReq = Request.Builder().url(addUrl).addHeader("Authorization", "Bearer $realDebridToken").post(formBody).build()
                    val addJson = JSONObject(client.newCall(addReq).execute().body?.string() ?: "{}")
                    val rdId = addJson.optString("id")
                    if (rdId.isEmpty()) continue
                    
                    val selectUrl = "https://api.real-debrid.com/rest/1.0/torrents/selectFiles/$rdId"
                    client.newCall(Request.Builder().url(selectUrl).addHeader("Authorization", "Bearer $realDebridToken").post(okhttp3.FormBody.Builder().add("files", "all").build()).build()).execute()
                    
                    var downloadUrl: String? = null
                    var attempts = 0
                    while (attempts < 30 && downloadUrl == null) { // v23.6: Increased from 15 to 30 seconds
                        delay(1000)
                        attempts++
                        val infoJson = JSONObject(client.newCall(Request.Builder().url("https://api.real-debrid.com/rest/1.0/torrents/info/$rdId").addHeader("Authorization", "Bearer $realDebridToken").build()).execute().body?.string() ?: "{}")
                        val status = infoJson.optString("status")
                        Log.d("CinemaCatalog", "⏳ RD Poll [$attempts/30]: $status")
                        if (status == "downloaded" || status == "cached") {
                            val links = infoJson.optJSONArray("links")
                            if (links != null && links.length() > 0) {
                                val files = infoJson.optJSONArray("files")
                                var bestIdx = 0
                                var maxSize = 0L
                                files?.let { fList ->
                                    for (j in 0 until fList.length()) {
                                        val f = fList.getJSONObject(j)
                                        if (f.optInt("selected", 0) == 1 && f.optLong("bytes", 0L) > maxSize) {
                                            maxSize = f.optLong("bytes", 0L)
                                            bestIdx = j
                                        }
                                    }
                                }
                                val link = if (bestIdx < links.length()) links.getString(bestIdx) else links.getString(0)
                                val unJson = JSONObject(client.newCall(Request.Builder().url("https://api.real-debrid.com/rest/1.0/unrestrict/link").addHeader("Authorization", "Bearer $realDebridToken").post(okhttp3.FormBody.Builder().add("link", link).build()).build()).execute().body?.string() ?: "{}")
                                val final = unJson.optString("download")
                                if (final.isNotEmpty()) downloadUrl = final
                            }
                        } else if (status == "error") break
                    }
                    if (downloadUrl != null) return@withContext downloadUrl
                }
            }
        } catch (e: Exception) {
            Log.e("CinemaCatalog", "RD processStreams error: ${e.message}")
        }
        null
    }
    
    /**
     * v16.0: Quick check for RD Availability (Instant Cache)
     */
    private suspend fun isCachedInRD(infoHash: String): Boolean = withContext(Dispatchers.IO) {
        if (realDebridToken.isEmpty()) return@withContext false
        try {
            val url = "https://api.real-debrid.com/rest/1.0/torrents/instantAvailability/$infoHash"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $realDebridToken")
                .build()
            
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: "{}"
            val json = JSONObject(body)
            
            // RD returns { hash: { hoster: [files] } }
            // If the hash key exists and has at least one hoster with files, it's cached.
            if (json.has(infoHash.lowercase())) {
                val hosters = json.getJSONObject(infoHash.lowercase())
                return@withContext hosters.length() > 0
            }
        } catch (e: Exception) {
            Log.e("CinemaCatalog", "RD Cache check error: ${e.message}")
        }
        false
    }

    /**
     * v16.1: Filters content to show only playable Spanish/Multi/Sub items.
     * Uses a Semaphore to limit parallel requests and avoid rate-limiting.
     */
    private val filterSemaphore = Semaphore(3) // Only 3 concurrent playability checks

    private suspend fun filterPlayable(items: List<ContentItem>, isTv: Boolean): List<ContentItem> = coroutineScope {
        if (items.isEmpty()) return@coroutineScope emptyList<ContentItem>()

        val deferredResults = items.take(15).map { item -> // Limit to 15 items for speed
            async(Dispatchers.IO) {
                filterSemaphore.withPermit {
                    if (item.provider == "archive" || item.videoUrl != null) {
                        return@async item
                    }
                    
                    if (item.provider == "consumet") {
                        return@async item
                    }

                    try {
                        val imdbId = getImdbId(item.providerId, isTv) ?: return@async null
                        val scraperPath = if (isTv) "series/$imdbId:1:1" else "movie/$imdbId"
                        val scraperUrl = "https://torrentio.strem.fun/stream/$scraperPath.json"
                        
                        val request = Request.Builder().url(scraperUrl).build()
                        val response = client.newCall(request).execute()
                        val body = response.body?.string() ?: return@async null
                        val json = JSONObject(body)
                        val streams = json.optJSONArray("streams") ?: return@async null
                        
                        for (i in 0 until minOf(streams.length(), 15)) {
                            val s = streams.getJSONObject(i)
                            val title = s.optString("title").lowercase()
                            val infoHash = s.optString("infoHash")
                            
                            // Check for Spanish/Multi/Sub and RD Cache
                            val isSpanish = title.contains("latino") || title.contains("español") || 
                                          title.contains("spanish") || title.contains("multi") ||
                                          title.contains("castellano") || title.contains("subs") ||
                                          title.contains("vose")
                            
                            if (isSpanish && infoHash.isNotEmpty()) {
                                if (isCachedInRD(infoHash)) {
                                    return@async item
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("CinemaCatalog", "Filter error for ${item.title}: ${e.message}")
                    }
                    null
                }
            }
        }
        
        deferredResults.awaitAll().filterNotNull()
    }


    /**
     * Validates if a URL is a playable video link
     */
    private fun isValidVideoUrl(url: String): Boolean {
        val validExtensions = listOf(".mp4", ".mkv", ".avi", ".m3u8", ".webm")
        return validExtensions.any { url.contains(it, ignoreCase = true) } || 
               url.contains("download", ignoreCase = true)
    }



    private fun fetchFromTmdb(url: String, isTv: Boolean = false, providerOverride: String? = null): List<ContentItem> {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        val json = JSONObject(response.body?.string() ?: "{}")
        val results = json.optJSONArray("results") ?: return emptyList()
        
        val items = mutableListOf<ContentItem>()
        // Fetch up to 20 items so we have more candidates for filtering
        for (i in 0 until minOf(results.length(), 20)) {
            val obj = results.getJSONObject(i)
            val title = if (isTv) obj.optString("name", "Serie") else obj.optString("title", "Película")
            items.add(ContentItem(
                title = title,
                posterUrl = "https://image.tmdb.org/t/p/w500${obj.optString("poster_path", "")}",
                videoUrl = null,
                provider = providerOverride ?: (if (isTv) "tmdb_tv" else "tmdb"),
                providerId = obj.optInt("id").toString()
            ))
        }
        return items
    }
}
