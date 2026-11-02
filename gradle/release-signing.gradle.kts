// Ordinary validation builds are unsigned. Any supplied production setting or an
// explicit signing request requires the complete set; there is no debug fallback.
val signingNames = listOf(
    "WRISTPROMPT_RELEASE_STORE_FILE", "WRISTPROMPT_RELEASE_STORE_PASSWORD",
    "WRISTPROMPT_RELEASE_KEY_ALIAS", "WRISTPROMPT_RELEASE_KEY_PASSWORD",
)
val signingValues = signingNames.associateWith { providers.environmentVariable(it).orNull }
val requirement = providers.environmentVariable("WRISTPROMPT_REQUIRE_RELEASE_SIGNING").orNull
if (requirement != null && requirement !in setOf("true", "false"))
    throw GradleException("WRISTPROMPT_REQUIRE_RELEASE_SIGNING must be true or false")
val requested = requirement == "true"
if (requested || signingValues.values.any { it != null }) {
    val missing = signingNames.filter { signingValues[it].isNullOrEmpty() }
    if (missing.isNotEmpty()) throw GradleException("Production signing requires: ${missing.joinToString()}")
    val keyFile = file(signingValues.getValue(signingNames[0])!!)
    if (!keyFile.isFile) throw GradleException("Production signing keystore is unavailable")
}
