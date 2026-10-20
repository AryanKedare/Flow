package io.github.aedev.flow.platform

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppUiModeTest {
    @Test
    fun `automatic resolves to tv on television devices`() {
        assertThat(AppUiMode.AUTOMATIC.resolve(DeviceFormFactor.TV, tvEdition = false)).isEqualTo(AppUiRoot.TV)
    }

    @Test
    fun `automatic resolves to mobile on non television devices`() {
        assertThat(AppUiMode.AUTOMATIC.resolve(DeviceFormFactor.MOBILE, tvEdition = false)).isEqualTo(AppUiRoot.MOBILE)
    }

    @Test
    fun `manual modes override detected form factor`() {
        assertThat(AppUiMode.MOBILE.resolve(DeviceFormFactor.TV, tvEdition = false)).isEqualTo(AppUiRoot.MOBILE)
        assertThat(AppUiMode.TV.resolve(DeviceFormFactor.MOBILE, tvEdition = false)).isEqualTo(AppUiRoot.TV)
    }

    @Test
    fun `TV edition stays remote friendly even after a mobile preference is restored`() {
        AppUiMode.entries.forEach { mode ->
            DeviceFormFactor.entries.forEach { device ->
                assertThat(mode.resolve(device, tvEdition = true)).isEqualTo(AppUiRoot.TV)
            }
        }
    }

    @Test
    fun `unknown stored value falls back to automatic`() {
        assertThat(AppUiMode.fromStorage("unknown")).isEqualTo(AppUiMode.AUTOMATIC)
        assertThat(AppUiMode.fromStorage(null)).isEqualTo(AppUiMode.AUTOMATIC)
    }
}
