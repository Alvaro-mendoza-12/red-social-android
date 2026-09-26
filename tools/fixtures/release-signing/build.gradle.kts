// Isolated test of the exact guard used by :app, without Android or real keys.
extra["novvinConfiguredReleaseSigning"] =
    providers.gradleProperty("fixtureConfiguredSigning").orNull == "true"
apply(from = file("../../../gradle/release-signing-validation.gradle.kts"))
tasks.register("preReleaseBuild")
