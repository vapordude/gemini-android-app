package nz.kaimahi.emdash

import nz.kaimahi.domain.EmdashClient
import nz.kaimahi.domain.EmdashDiff
import nz.kaimahi.domain.EmdashProfile

class RustEmdashClient : EmdashClient {

    override suspend fun listProfiles(): List<EmdashProfile> = emptyList()

    override suspend fun listCollections(profile: String): List<String> = emptyList()

    override suspend fun previewDiff(profile: String, payload: String): EmdashDiff = EmdashDiff()

    fun nativeVersion(): String = NativeEmdash.clientVersion()
}
