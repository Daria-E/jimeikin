package com.wanderwildwood.jimeikin.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.wanderwildwood.jimeikin.R

/** The address of the app's own source code. */
const val SOURCE_URL = "https://github.com/wanderwildwood/jimeikin"

/** What the APK carries that is not this app's own code, and the licence each project declares. */
val LICENCE_COMPONENTS: List<LicenceComponent> = listOf(
    LicenceComponent("CalmMusic (this app is a fork)", "David Ray Wilson", "GPL-3.0", "https://github.com/wanderwildwood/jimeikin"),
    LicenceComponent("jaudiotagger", "Paul Taylor and contributors", "LGPL-2.1", "https://github.com/ijabz/jaudiotagger"),
    LicenceComponent("Mudita MMD", "Mudita", "Apache-2.0", "https://developer.mudita.com"),
    LicenceComponent("AndroidX, Jetpack Compose, Media3, Room", "Google", "Apache-2.0", "https://developer.android.com/jetpack/androidx"),
    LicenceComponent("OkHttp and Okio", "Square", "Apache-2.0", "https://github.com/square/okhttp"),
    LicenceComponent("Kotlin and kotlinx.coroutines", "JetBrains", "Apache-2.0", "https://github.com/Kotlin/kotlinx.coroutines"),
    LicenceComponent("desugar_jdk_libs", "Google", "GPL-2.0 with the Classpath Exception", "https://github.com/google/desugar_jdk_libs"),
    LicenceComponent("Lato typeface", "tyPoland Lukasz Dziedzic", "OFL-1.1", "https://www.latofonts.com"),
    LicenceComponent("Material Symbols icons", "Google", "Apache-2.0", "https://fonts.google.com/icons"),
)

/** Each distinct licence text once: family id and the raw resource. */
val LICENCE_RAW: List<Pair<String, Int>> = listOf(
    "gpl" to R.raw.licence_gpl3,
    "gpl2-cpe" to R.raw.licence_gpl2_cpe,
    "lgpl" to R.raw.licence_lgpl21,
    "apache" to R.raw.licence_apache2,
    "ofl" to R.raw.licence_ofl,
)

@Composable
fun rememberLicencesData(): LicencesData = LicencesData(
    appName = "Music Box",
    sourceUrl = SOURCE_URL,
    licenceLine = stringResource(R.string.licences_licence_body),
    components = LICENCE_COMPONENTS,
    texts = LICENCE_RAW.map { (id, raw) ->
        LicenceText(id, stringResource(when (id) {
            "gpl" -> R.string.licences_full_gpl
            "gpl2-cpe" -> R.string.licences_full_gpl2_cpe
            "lgpl" -> R.string.licences_full_lgpl
            "apache" -> R.string.licences_full_apache
            "ofl" -> R.string.licences_full_ofl
            else -> R.string.licences_texts_heading
        }), raw)
    },
)

@Composable
fun rememberLicencesLabels(): LicencesLabels {
    val none = stringResource(R.string.about_no_browser_address, "@@")
    val noText = stringResource(R.string.licences_no_text, "@@")
    return LicencesLabels(
        title = stringResource(R.string.licences_title),
        back = stringResource(R.string.licences_back),
        sourceHeading = stringResource(R.string.licences_source_heading),
        noText = { licence -> noText.replace("@@", licence) },
        noBrowser = { address -> none.replace("@@", address) },
    )
}
