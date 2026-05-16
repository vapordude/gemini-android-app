package nz.kaimahi.domain

/**
 * Raw attachment bytes + MIME type for a multimodal user turn. Gemini's
 * REST endpoint accepts `inlineData` parts up to ~20 MB per request; this
 * type is what both the remote and local drivers consume.
 *
 * `localPath` is optional and only used by the UI layer for thumbnail
 * rendering. Drivers never read it.
 */
data class Attachment(
    val bytes: ByteArray,
    val mimeType: String,
    val localPath: String? = null,
) {
    override fun equals(other: Any?) = other is Attachment &&
        mimeType == other.mimeType && bytes.contentEquals(other.bytes) &&
        localPath == other.localPath
    override fun hashCode(): Int {
        var h = 31 * mimeType.hashCode() + bytes.contentHashCode()
        h = 31 * h + (localPath?.hashCode() ?: 0)
        return h
    }
}
