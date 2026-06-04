package conectate.com.auth.twoFactor

/**
 * warrenstrange/googleauth defaults to the desktop JVM "SUN" SecureRandom provider,
 * which is not available on Android and crashes app startup when [GoogleAuthenticator]
 * is constructed (e.g. via Hilt injection in MainActivity).
 */
internal fun configureGoogleAuthForAndroid() {
    System.setProperty("com.warrenstrange.googleauth.rng.algorithmProvider", "")
}
