package conectate.com.presentation.profile

internal fun attemptAdminPanelNavigation(
    navigate: () -> Unit,
    onFailure: (Throwable) -> Unit
): Boolean {
    return runCatching(navigate)
        .onFailure(onFailure)
        .isSuccess
}
