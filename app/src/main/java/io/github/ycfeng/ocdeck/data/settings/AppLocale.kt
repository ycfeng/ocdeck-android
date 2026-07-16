package io.github.ycfeng.ocdeck.data.settings

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

fun Context.localized(preference: AppLanguagePreference): Context {
    val locale = when (preference) {
        AppLanguagePreference.System -> return this
        AppLanguagePreference.English -> Locale.forLanguageTag("en")
        AppLanguagePreference.SimplifiedChinese -> Locale.forLanguageTag("zh-Hans")
    }
    val configuration = Configuration(resources.configuration)
    configuration.setLocales(LocaleList(locale))
    return createConfigurationContext(configuration)
}
