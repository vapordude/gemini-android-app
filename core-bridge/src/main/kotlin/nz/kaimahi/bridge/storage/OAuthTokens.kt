package nz.kaimahi.bridge.storage

data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiryEpochMs: Long,
    val projectId: String?,
) {
    fun expiresWithin(windowMs: Long = 5 * 60 * 1000L): Boolean =
        System.currentTimeMillis() >= (expiryEpochMs - windowMs)
}
