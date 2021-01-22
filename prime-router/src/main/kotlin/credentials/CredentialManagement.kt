package gov.cdc.prime.router.credentials

interface CredentialManagement {
    @Suppress("unused")
    val credentialService: CredentialService
        get() = credentialServiceForStorageMethod()
}

internal fun credentialServiceForStorageMethod(): CredentialService {
    when (System.getProperty("CREDENTIAL_STORAGE_METHOD")) {
        "AZURE" -> return AzureCredentialService
        "HASHICORP_VAULT" -> return HashicorpVaultCredentialService
        else -> return MemoryCredentialService
    }
}