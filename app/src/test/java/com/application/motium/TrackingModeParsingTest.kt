import com.application.motium.domain.model.TrackingMode
import com.application.motium.domain.model.toTrackingModeOrDefault
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackingModeParsingTest {

    @Test
    fun testTrackingModeParsing_ValidValue() {
        val mode = "ALWAYS".toTrackingModeOrDefault()
        assertEquals(TrackingMode.ALWAYS, mode)
    }

    @Test
    fun testTrackingModeParsing_InvalidValueDefaultsToDisabled() {
        val mode = "MANUAL".toTrackingModeOrDefault()
        assertEquals(TrackingMode.DISABLED, mode)
    }

    @Test
    fun testTrackingModeParsing_BlankDefaultsToDisabled() {
        val mode = "  ".toTrackingModeOrDefault()
        assertEquals(TrackingMode.DISABLED, mode)
    }

    @Test
    fun testTrackingModeParsing_CustomDefault() {
        val mode = "INVALID".toTrackingModeOrDefault(TrackingMode.WORK_HOURS_ONLY)
        assertEquals(TrackingMode.WORK_HOURS_ONLY, mode)
    }
}
