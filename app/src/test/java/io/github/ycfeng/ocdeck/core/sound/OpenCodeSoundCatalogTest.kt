package io.github.ycfeng.ocdeck.core.sound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenCodeSoundCatalogTest {
    @Test
    fun exposesWebSoundOptionsInOrder() {
        val expectedIds = numberedIds("alert", 10) +
            numberedIds("bip-bop", 10) +
            numberedIds("staplebops", 7) +
            numberedIds("nope", 12) +
            numberedIds("yup", 6)

        assertEquals(45, OpenCodeSoundCatalog.soundOptions.size)
        assertEquals(expectedIds, OpenCodeSoundCatalog.soundOptions.map { it.id })
    }

    @Test
    fun selectableOptionsStartWithNone() {
        assertEquals(46, OpenCodeSoundCatalog.selectableOptions.size)
        assertEquals(OpenCodeSoundCatalog.NoneSoundId, OpenCodeSoundCatalog.selectableOptions.first().id)
        assertNull(OpenCodeSoundCatalog.noneOption.rawResId)
        assertNull(OpenCodeSoundCatalog.noneOption.rawResourceName)
        assertNull(OpenCodeSoundCatalog.rawResId(OpenCodeSoundCatalog.NoneSoundId))
    }

    @Test
    fun defaultsMatchWebSettings() {
        assertEquals("staplebops-01", OpenCodeSoundCatalog.DefaultAgentSoundId)
        assertEquals("staplebops-02", OpenCodeSoundCatalog.DefaultPermissionsSoundId)
        assertEquals("nope-03", OpenCodeSoundCatalog.DefaultErrorsSoundId)
    }

    @Test
    fun mapsSoundIdsToRawResourceNames() {
        assertEquals(
            "bip_bop_01",
            OpenCodeSoundCatalog.soundOptionForId("bip-bop-01")?.rawResourceName,
        )
        assertEquals(
            "staplebops_07",
            OpenCodeSoundCatalog.soundOptionForId("staplebops-07")?.rawResourceName,
        )
        assertEquals(
            "nope_12",
            OpenCodeSoundCatalog.soundOptionForId("nope-12")?.rawResourceName,
        )
    }

    @Test
    fun fallsBackToCategoryDefaultWhenSelectedIdIsUnknown() {
        assertEquals(
            "nope-03",
            OpenCodeSoundCatalog.selectedOption(
                enabled = true,
                id = "missing",
                defaultId = OpenCodeSoundCatalog.DefaultErrorsSoundId,
            ).id,
        )
        assertEquals(
            OpenCodeSoundCatalog.NoneSoundId,
            OpenCodeSoundCatalog.selectedOption(
                enabled = false,
                id = OpenCodeSoundCatalog.DefaultErrorsSoundId,
                defaultId = OpenCodeSoundCatalog.DefaultErrorsSoundId,
            ).id,
        )
    }

    private fun numberedIds(prefix: String, count: Int): List<String> = (1..count).map { number ->
        "$prefix-${number.toString().padStart(2, '0')}"
    }
}
