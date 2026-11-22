package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ThaiZontesModelsTest {
    @Test fun knownThaiQrModelsUseRideScreenCanvas() {
        assertEquals(1024 to 425, ThaiZontesModels.byQrModelId("21334")?.panel)
        assertEquals(1024 to 464, ThaiZontesModels.byQrModelId("21313")?.panel)
        assertEquals(1024 to 464, ThaiZontesModels.byQrModelId("21321")?.panel)
        assertTrue(ThaiZontesModels.byQrModelId(" 21334 ")!!.motoPlayBaseline)
    }

    @Test fun threeThai368ChoicesUseTheApkFallback() {
        for (model in listOf(ThaiZontesModel.Z368E, ThaiZontesModel.Z368G, ThaiZontesModel.Z368K)) {
            assertEquals(1024 to 425, ThaiZontesModels.screenHint(null, model)?.panel)
        }
        assertNull(ThaiZontesModels.screenHint(null, ThaiZontesModel.Z368D))
    }

    @Test fun observed150xDoesNotInheritThe368Canvas() {
        assertNull(ThaiZontesModels.screenHint("48406", ThaiZontesModel.Z368K))
        assertNull(ThaiZontesModels.screenHint("48406", ThaiZontesModel.Z150X))
    }

    @Test fun observedCanvasOverridesDefaultsAndQrOverridesThePicker() {
        val known = ThaiZontesModels.screenHint("21313", ThaiZontesModel.Z368K)
        assertEquals(1024 to 464, known?.panel)
        assertEquals(800 to 368, ThaiZontesModels.preferMeasured(800 to 368, known))
        assertNull(ThaiZontesModels.preferMeasured(null, null))
    }

    @Test fun modelListStaysWithinThaiScope() {
        assertEquals(setOf("Z150X", "Z350D", "Z350E", "Z368D", "Z368E", "Z368G", "Z368K"),
            ThaiZontesModel.entries.map { it.name }.toSet())
    }

    @Test fun qrSelectionUsesKnownProfilesAndKeeps150x() {
        fun qr(id: String) = QrData("ZT_TEST", "", null, null, null, 1, id, null, null)
        for (id in listOf("21334", "21313", "21321", "368K")) {
            assertSame(Cfdl16MotoPlayLandscapeProfile, BikeProfiles.selectByQr(qr(id)))
        }
        val observed150x = QrData("UNKNOWN_SSID", "", null, null, null, 1, "48406", null, null)
        assertSame(ZontesProfile, BikeProfiles.selectByQr(observed150x))
    }

    @Test fun androidAutoUsesCurrentBikePanelInsteadOfPreviousBike() {
        assertEquals(1024 to 425, VideoPrefs.autoPanel(null, 1024 to 425, 800 to 368))
        assertEquals(800 to 368, VideoPrefs.autoPanel(800 to 368, 1024 to 425, 800 to 480))
        assertEquals(800 to 480, VideoPrefs.autoPanel(null, null, 800 to 480))
        val panel = VideoPrefs.autoPanel(null, 1024 to 425, 800 to 368)!!
        val margins = AaMargins.forAspect(AaVideoSpec(AaResolution.LANDSCAPE_800x480, 160), panel.first, panel.second)
        assertEquals(0, margins.marginW)
        assertTrue(margins.marginH > 0)
    }
}
