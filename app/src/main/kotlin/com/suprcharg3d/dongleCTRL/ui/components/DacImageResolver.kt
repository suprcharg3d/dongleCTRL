package com.suprcharg3d.dongleCTRL.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.suprcharg3d.dongleCTRL.protocol.DacProfile
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Offline resolver for bundled DAC product WebP images (`assets/dac_images/`).
 * Maps EPZ (by VID/PID, excluding H10), TRN Black Pearl (`trnbp.webp`), and FiiO/JadeAudio/SNOWSKY
 * models (by `fiioDeviceType` or normalized model name), falling back to null when unavailable.
 */
internal object DacImageResolver {

    private data class ImageIndex(
        val vidPid: Map<String, String>,
        val fiioDeviceType: Map<Int, String>,
        val model: Map<String, String>
    )

    @Volatile
    private var cachedIndex: ImageIndex? = null
    private val bitmapCache = ConcurrentHashMap<String, ImageBitmap>()

    private fun getIndex(context: Context): ImageIndex {
        cachedIndex?.let { return it }
        return synchronized(this) {
            cachedIndex ?: loadIndex(context.applicationContext).also { cachedIndex = it }
        }
    }

    private fun loadIndex(context: Context): ImageIndex {
        return runCatching {
            val raw = context.assets.open("dac_image_map.json").bufferedReader().use { it.readText() }
            val root = JSONObject(raw)

            val vpObj = root.optJSONObject("vidPid")
            val vpMap = HashMap<String, String>()
            vpObj?.keys()?.forEach { k -> vpMap[k] = vpObj.getString(k) }

            val dtObj = root.optJSONObject("fiioDeviceType")
            val dtMap = HashMap<Int, String>()
            dtObj?.keys()?.forEach { k ->
                k.toIntOrNull()?.let { id -> dtMap[id] = dtObj.getString(k) }
            }

            val mObj = root.optJSONObject("model")
            val mMap = HashMap<String, String>()
            mObj?.keys()?.forEach { k -> mMap[k] = mObj.getString(k) }

            ImageIndex(vpMap, dtMap, mMap)
        }.getOrElse {
            ImageIndex(emptyMap(), emptyMap(), emptyMap())
        }
    }

    fun resolveAssetFile(context: Context, profile: DacProfile): String? {
        val idx = getIndex(context)
        val vid = profile.vendorId
        val pid = profile.productId ?: 0
        if (vid != 0 && pid != 0) {
            val vpKey = "%04x_%04x".format(vid and 0xFFFF, pid and 0xFFFF)
            if (vpKey == EXCLUDED_EPZ_H10_VID_PID) return null
            idx.vidPid[vpKey]?.let { return it }
        }
        profile.fiioDeviceType?.let { dt ->
            idx.fiioDeviceType[dt]?.let { return it }
        }
        val allowModelFallback = profile.source in ALLOWED_MODEL_SOURCES ||
            profile.brand.equals("FiiO", ignoreCase = true) ||
            profile.brand.equals("JadeAudio", ignoreCase = true) ||
            profile.brand.equals("SNOWSKY", ignoreCase = true) ||
            profile.brand.equals("EPZ", ignoreCase = true) ||
            profile.brand.equals("TANCHJIM", ignoreCase = true) ||
            profile.brand.equals("NICEHCK", ignoreCase = true) ||
            profile.brand.equals("MOONDROP", ignoreCase = true) ||
            (profile.brand.equals("TRN", ignoreCase = true) && profile.model.contains("Black Pearl", ignoreCase = true))
        if (!allowModelFallback) return null

        val key = normalizeModelName(profile.model)
        if (key == "h10" && profile.brand.equals("EPZ", ignoreCase = true)) return null
        return idx.model[key]
    }

    fun loadBitmap(context: Context, profile: DacProfile): ImageBitmap? {
        val filename = resolveAssetFile(context, profile) ?: return null
        bitmapCache[filename]?.let { return it }
        val appCtx = context.applicationContext
        val loaded = runCatching {
            appCtx.assets.open("dac_images/$filename").use { stream ->
                BitmapFactory.decodeStream(stream)?.asImageBitmap()
            }
        }.getOrNull() ?: return null
        bitmapCache.putIfAbsent(filename, loaded)
        return loaded
    }

    private fun normalizeModelName(raw: String): String {
        var s = raw.trim().lowercase()
        for (prefix in BRAND_PREFIXES) {
            if (s.startsWith(prefix)) {
                s = s.removePrefix(prefix)
            }
        }
        return s.replace(NON_ALNUM_REGEX, "")
    }

    private const val EXCLUDED_EPZ_H10_VID_PID = "5ac1_1111"
    private val ALLOWED_MODEL_SOURCES = setOf("fiio", "fiio_bt", "epz", "tanchjim", "nicehck", "moondrop")
    private val BRAND_PREFIXES = listOf(
        "fiio ", "jadeaudio ", "snowsky ",
        "tanchjim-", "tanchjim ",
        "nicehck-", "nicehck ",
        "moondrop-", "moondrop "
    )
    private val NON_ALNUM_REGEX = Regex("[^a-z0-9]+")
}
