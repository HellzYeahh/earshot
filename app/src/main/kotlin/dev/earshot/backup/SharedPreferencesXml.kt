package dev.earshot.backup

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * The SharedPreferences file a backup carries (`whats_new.xml`) is the one restored file that is
 * not rebuilt: it is copied byte for byte. Android reads it at every start with a pull parser
 * that processes a DOCTYPE (`Xml.newPullParser()` turns on `FEATURE_PROCESS_DOCDECL`), so a
 * crafted file could declare nested entities that expand to gigabytes each time the app starts.
 *
 * SharedPreferences itself writes a UTF-8 declaration and one `<map>`, and escapes every `<` in a
 * value (`&lt;`): a real file never holds `<!` (DOCTYPE, ENTITY, comments, CDATA) or a second
 * `<?`. Anything else is refused as damaged.
 */
internal object SharedPreferencesXml {
    private val ENCODING = Regex("""encoding\s*=\s*['"]([^'"]*)['"]""")

    fun isPlain(bytes: ByteArray): Boolean {
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            return false
        }
        val body = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (!body.startsWith("<?xml")) return false
        val declarationEnd = body.indexOf("?>")
        if (declarationEnd < 0) return false
        // Another declared encoding (UTF-7, say) could spell `<!DOCTYPE` in bytes this check never sees as `<!`.
        val encoding = ENCODING.find(body.substring(0, declarationEnd))?.groupValues?.get(1)
        if (encoding != null && !encoding.equals("utf-8", ignoreCase = true)) return false
        val rest = body.substring(declarationEnd + 2)
        return "<map" in rest && "<!" !in body && "<?" !in rest
    }
}
