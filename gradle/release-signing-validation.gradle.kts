// This guard validates configuration availability, not the keystore password or
// Play Console certificate. AGP validates/signs with the original selected key.
val configuredReleaseSigning = extra["novvinConfiguredReleaseSigning"] as Boolean
val injectedSigningFields = linkedMapOf(
    "store.file" to "archivo de llave",
    "store.password" to "contraseña del almacén",
    "key.alias" to "alias de llave",
    "key.password" to "contraseña de llave",
)
val injectedSigningValues = injectedSigningFields.keys.associateWith {
    providers.gradleProperty("android.injected.signing.$it").orNull
}
val hasInjectedSigning = injectedSigningValues.values.any { it != null }
val missingInjectedSigning = injectedSigningFields.filter { (field, _) ->
    val value = injectedSigningValues[field]
    if (field.endsWith("password")) value.isNullOrEmpty() else value.isNullOrBlank()
}.values.toList()
val injectedStoreFile = injectedSigningValues["store.file"]
    ?.takeIf { it.isNotBlank() }?.let { rootProject.file(it) }

val verifyReleaseSigningConfiguration = tasks.register("verifyReleaseSigningConfiguration") {
    group = "verification"
    description = "Requires complete release signing from Android Studio, local configuration or CI."
    doLast {
        // Never mix a partial wizard selection with a different CLI key.
        if (hasInjectedSigning) {
            if (missingInjectedSigning.isNotEmpty()) {
                throw GradleException(
                    "Firma de Android Studio incompleta. Completa en Generate Signed Bundle: " +
                        missingInjectedSigning.joinToString() + ". No se utilizará otra llave automáticamente."
                )
            }
            if (injectedStoreFile?.isFile != true || !injectedStoreFile.canRead()) {
                throw GradleException(
                    "El archivo de llave seleccionado en Android Studio no existe o no se puede leer. " +
                        "Selecciona el archivo original, no la carpeta."
                )
            }
        } else if (!configuredReleaseSigning) {
            throw GradleException(
                "Release signing is required. Use Android Studio > Generate Signed Bundle with the original " +
                    "Play upload key, or configure keystore.properties / NOVVIN_UPLOAD_* for CLI and CI builds."
            )
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyReleaseSigningConfiguration)
}
