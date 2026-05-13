package com.gemini.emdash

import com.gemini.domain.EmdashClient
import com.gemini.domain.EmdashDiff
import com.gemini.domain.EmdashProfile

class RustEmdashClient : EmdashClient {

    override suspend fun listProfiles(): List<EmdashProfile> = emptyList()

    override suspend fun listCollections(profile: String): List<String> = emptyList()

    override suspend fun previewDiff(profile: String, payload: String): EmdashDiff = EmdashDiff()

    fun nativeVersion(): String = NativeEmdash.clientVersion()
}
