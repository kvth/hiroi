package io.github.kvth.hiroi.dictionary

import org.json.JSONArray
import org.json.JSONObject

/** An image of a dictionary, as Yomitan keeps it in its media table; [read] gives its bytes. */
class DictionaryMedia(val path: String, val mediaType: String, val width: Int, val height: Int, val read: () -> ByteArray)

/** Dictionary images the way Yomitan imports them (dictionary-importer.js, media-util.js). */
object DictionaryImages {
    /** The media type of an image file, by its extension, or null if it isn't one Yomitan takes. */
    fun mediaType(path: String): String? = when (path.substringAfterLast('.', "").lowercase()) {
        "apng" -> "image/apng"
        "avif" -> "image/avif"
        "bmp" -> "image/bmp"
        "gif" -> "image/gif"
        "ico", "cur" -> "image/x-icon"
        "jpg", "jpeg", "jfif", "pjpeg", "pjp" -> "image/jpeg"
        "png" -> "image/png"
        "svg" -> "image/svg+xml"
        "tif", "tiff" -> "image/tiff"
        "webp" -> "image/webp"
        else -> null
    }

    /** An SVG's size from its width/height, or else its viewBox; null if it states neither. */
    fun svgSize(svg: String): Pair<Int, Int>? {
        val root = Regex("<svg\\b[^>]*>", RegexOption.IGNORE_CASE).find(svg)?.value ?: return null
        fun attribute(name: String) = Regex("\\b$name\\s*=\\s*[\"']\\s*([\\d.]+)").find(root)?.groupValues?.get(1)?.toDoubleOrNull()
        val width = attribute("width")
        val height = attribute("height")
        if (width != null && height != null) return width.toInt() to height.toInt()
        val box = Regex("\\bviewBox\\s*=\\s*[\"']([^\"']+)").find(root)?.groupValues?.get(1)
            ?.trim()?.split(Regex("[\\s,]+"))?.mapNotNull { it.toDoubleOrNull() }
        return if (box != null && box.size == 4) box[2].toInt() to box[3].toInt() else null
    }

    /**
     * A glossary (term bank JSON array) with its images as Yomitan stores them after importing:
     * width and height become the image's own size ([size] of its path), and those the
     * dictionary gave become preferredWidth / preferredHeight. Images the dictionary doesn't
     * include are left as they are.
     */
    fun prepare(glossary: String, size: (String) -> Pair<Int, Int>?): String {
        if (!glossary.contains("\"path\"")) return glossary
        val items = JSONArray(glossary)
        var changed = false
        for (i in 0 until items.length()) {
            val item = items.opt(i) as? JSONObject ?: continue
            when (item.optString("type")) {
                "image" -> image(item, size)?.let { items.put(i, it.put("type", "image")); changed = true }
                "structured-content" -> if (structured(item, "content", size)) changed = true
            }
        }
        return if (changed) items.toString() else glossary
    }

    /** Rewrites the img elements under [parent]'s [key]; true if any changed. */
    private fun structured(parent: Any, key: Any, size: (String) -> Pair<Int, Int>?): Boolean {
        val node = when (parent) {
            is JSONObject -> parent.opt(key as String)
            is JSONArray -> parent.opt(key as Int)
            else -> null
        }
        return when (node) {
            is JSONArray -> (0 until node.length()).map { structured(node, it, size) }.any { it }
            is JSONObject -> if (node.optString("tag") == "img") {
                val image = image(node, size) ?: return false
                image.put("tag", "img")
                for (extra in listOf("verticalAlign", "border", "borderRadius", "sizeUnits")) if (node.has(extra)) image.put(extra, node.get(extra))
                when (parent) {
                    is JSONObject -> parent.put(key as String, image)
                    is JSONArray -> parent.put(key as Int, image)
                }
                true
            } else {
                structured(node, "content", size)
            }
            else -> false
        }
    }

    /** dictionary-importer.js, _createImageData; null if the image isn't in the dictionary. */
    private fun image(source: JSONObject, size: (String) -> Pair<Int, Int>?): JSONObject? {
        val path = source.optString("path").takeIf { it.isNotEmpty() } ?: return null
        val (width, height) = size(path) ?: return null
        val target = JSONObject().put("path", path).put("width", width).put("height", height)
        (source.opt("width") as? Number)?.let { target.put("preferredWidth", it) }
        (source.opt("height") as? Number)?.let { target.put("preferredHeight", it) }
        for (key in listOf("title", "alt", "description", "imageRendering", "appearance")) (source.opt(key) as? String)?.let { target.put(key, it) }
        for (key in listOf("pixelated", "background", "collapsed", "collapsible")) (source.opt(key) as? Boolean)?.let { target.put(key, it) }
        return target
    }
}
