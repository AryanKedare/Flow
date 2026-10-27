package io.github.aedev.flow.data.profile

/**
 * Closed while this process switches profile. Until it exits, nothing from outside the app (the system media
 * controls, a car, a widget) may start or browse the old profile's music. It only reopens if the switch fails.
 */
object ProfileSwitchGate {
    @Volatile
    var isSwitching: Boolean = false
        private set

    internal fun close() {
        isSwitching = true
    }

    internal fun reopen() {
        isSwitching = false
    }
}
