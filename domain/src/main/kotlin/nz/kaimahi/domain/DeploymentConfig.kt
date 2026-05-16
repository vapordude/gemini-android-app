package nz.kaimahi.domain

enum class EmdashEnv { DEV, STAGING, PROD }

data class EmdashProfile(
    val name: String,
    val baseUrl: String,
    val env: EmdashEnv,
)

data class EmdashDiff(
    val adds: List<String> = emptyList(),
    val updates: List<String> = emptyList(),
    val deletes: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
)

data class DeploymentConfig(
    val name: String,
    val provider: String,
    val region: String?,
    val runtime: String,
    val envVars: Map<String, String> = emptyMap(),
    val buildCommand: String? = null,
    val postDeployHooks: List<String> = emptyList(),
)

interface EmdashClient {
    suspend fun listProfiles(): List<EmdashProfile>
    suspend fun listCollections(profile: String): List<String>
    suspend fun previewDiff(profile: String, payload: String): EmdashDiff
}
