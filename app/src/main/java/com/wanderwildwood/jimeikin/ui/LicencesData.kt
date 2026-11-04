package com.wanderwildwood.jimeikin.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.wanderwildwood.jimeikin.R

/** The address of the app's own source code. */
const val SOURCE_URL = "https://github.com/wanderwildwood/jimeikin"

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
    // One row per artifact in the APK, listed by the build (config/libraries adds what Gradle cannot see).
    components = rememberGeneratedComponents(R.raw.aboutlibraries),
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
