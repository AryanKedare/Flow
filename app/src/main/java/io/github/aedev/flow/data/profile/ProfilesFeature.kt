package io.github.aedev.flow.data.profile

import io.github.aedev.flow.BuildConfig

/** Profiles stay out of sight until they are finished; debug builds show them for testing. */
object ProfilesFeature {
    val isVisible: Boolean get() = BuildConfig.DEBUG
}
