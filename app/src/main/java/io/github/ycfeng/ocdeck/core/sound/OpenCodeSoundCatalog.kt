package io.github.ycfeng.ocdeck.core.sound

import androidx.annotation.RawRes
import androidx.annotation.StringRes
import io.github.ycfeng.ocdeck.R

data class OpenCodeSoundOption(
    val id: String,
    @StringRes val labelResId: Int,
    val labelNumber: Int? = null,
    @RawRes val rawResId: Int? = null,
) {
    val rawResourceName: String?
        get() = rawResId?.let { id.replace('-', '_') }
}

object OpenCodeSoundCatalog {
    const val NoneSoundId = "none"
    const val DefaultAgentSoundId = "staplebops-01"
    const val DefaultPermissionsSoundId = "staplebops-02"
    const val DefaultErrorsSoundId = "nope-03"

    val noneOption = OpenCodeSoundOption(
        id = NoneSoundId,
        labelResId = R.string.sound_option_none,
    )

    val soundOptions: List<OpenCodeSoundOption> = buildList {
        addNumbered("alert", R.string.sound_option_alert_number, alertRawResources())
        addNumbered("bip-bop", R.string.sound_option_bip_bop_number, bipBopRawResources())
        addNumbered("staplebops", R.string.sound_option_staplebops_number, staplebopsRawResources())
        addNumbered("nope", R.string.sound_option_nope_number, nopeRawResources())
        addNumbered("yup", R.string.sound_option_yup_number, yupRawResources())
    }

    val selectableOptions: List<OpenCodeSoundOption> = listOf(noneOption) + soundOptions

    fun isNone(id: String): Boolean = id == NoneSoundId

    fun optionForId(id: String?): OpenCodeSoundOption? = selectableOptions.firstOrNull { it.id == id }

    fun soundOptionForId(id: String?): OpenCodeSoundOption? = soundOptions.firstOrNull { it.id == id }

    fun rawResId(id: String?): Int? = soundOptionForId(id)?.rawResId

    fun soundIdOrDefault(id: String?, defaultId: String): String = soundOptionForId(id)?.id ?: defaultId

    fun selectedOption(enabled: Boolean, id: String, defaultId: String): OpenCodeSoundOption = if (enabled) {
        soundOptionForId(id) ?: soundOptionForId(defaultId) ?: noneOption
    } else {
        noneOption
    }

    private fun MutableList<OpenCodeSoundOption>.addNumbered(
        idPrefix: String,
        @StringRes labelResId: Int,
        rawResources: List<Int>,
    ) {
        rawResources.forEachIndexed { index, rawResId ->
            val number = index + 1
            add(
                OpenCodeSoundOption(
                    id = "$idPrefix-${number.toString().padStart(2, '0')}",
                    labelResId = labelResId,
                    labelNumber = number,
                    rawResId = rawResId,
                ),
            )
        }
    }

    private fun alertRawResources() = listOf(
        R.raw.alert_01,
        R.raw.alert_02,
        R.raw.alert_03,
        R.raw.alert_04,
        R.raw.alert_05,
        R.raw.alert_06,
        R.raw.alert_07,
        R.raw.alert_08,
        R.raw.alert_09,
        R.raw.alert_10,
    )

    private fun bipBopRawResources() = listOf(
        R.raw.bip_bop_01,
        R.raw.bip_bop_02,
        R.raw.bip_bop_03,
        R.raw.bip_bop_04,
        R.raw.bip_bop_05,
        R.raw.bip_bop_06,
        R.raw.bip_bop_07,
        R.raw.bip_bop_08,
        R.raw.bip_bop_09,
        R.raw.bip_bop_10,
    )

    private fun staplebopsRawResources() = listOf(
        R.raw.staplebops_01,
        R.raw.staplebops_02,
        R.raw.staplebops_03,
        R.raw.staplebops_04,
        R.raw.staplebops_05,
        R.raw.staplebops_06,
        R.raw.staplebops_07,
    )

    private fun nopeRawResources() = listOf(
        R.raw.nope_01,
        R.raw.nope_02,
        R.raw.nope_03,
        R.raw.nope_04,
        R.raw.nope_05,
        R.raw.nope_06,
        R.raw.nope_07,
        R.raw.nope_08,
        R.raw.nope_09,
        R.raw.nope_10,
        R.raw.nope_11,
        R.raw.nope_12,
    )

    private fun yupRawResources() = listOf(
        R.raw.yup_01,
        R.raw.yup_02,
        R.raw.yup_03,
        R.raw.yup_04,
        R.raw.yup_05,
        R.raw.yup_06,
    )
}
