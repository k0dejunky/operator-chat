package com.amethyst2213.operatorchat

/**
 * A new build of the app that the site is publishing.
 *
 * The app checks the published version manifest on its own (the manifest and
 * the APK are public), so an update is offered without the operator having to
 * go looking for it. Android does not allow an app to install itself
 * silently, so the offer ends in a tap on "Install" and the system installer.
 */
data class UpdateOffer(
    val version: String,
    val versionCode: Long,
    val notes: String = "",
    val phase: UpdatePhase = UpdatePhase.AVAILABLE,
) {
    /** What the operator is told. */
    val headline: String
        get() = when (phase) {
            UpdatePhase.AVAILABLE -> "Operator Chat $version is available"
            UpdatePhase.DOWNLOADING -> "Downloading Operator Chat $version..."
            UpdatePhase.VERIFYING -> "Verifying the download..."
            UpdatePhase.INSTALLING -> "Installing Operator Chat $version..."
            UpdatePhase.FAILED -> "The update could not be installed"
            UpdatePhase.INSTALLED -> "Operator Chat $version is installed"
        }
}

/** Where the update is in its journey. */
enum class UpdatePhase {
    AVAILABLE,
    DOWNLOADING,
    VERIFYING,
    INSTALLING,
    INSTALLED,
    FAILED,
}
