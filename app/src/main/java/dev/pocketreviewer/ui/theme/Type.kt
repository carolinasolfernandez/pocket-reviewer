package dev.pocketreviewer.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import dev.pocketreviewer.R

/*
 * Inter, bundled as a single variable font (res/font/inter.ttf, SIL Open Font
 * License — see licenses/Inter-OFL.txt). Each weight the UI uses is an instance of
 * that one file. Review text, rules and logs keep the system monospace font.
 */
@OptIn(ExperimentalTextApi::class)
private fun inter(weight: FontWeight) = Font(
    resId = R.font.inter,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val Inter = FontFamily(
    inter(FontWeight.Normal),
    inter(FontWeight.Medium),
    inter(FontWeight.SemiBold),
    inter(FontWeight.Bold),
)

private fun TextStyle.inInter(weight: FontWeight? = null, tracking: Double? = null) = copy(
    fontFamily = Inter,
    fontWeight = weight ?: fontWeight,
    letterSpacing = tracking?.let { androidx.compose.ui.unit.TextUnit(it.toFloat(), androidx.compose.ui.unit.TextUnitType.Em) }
        ?: letterSpacing,
)

private val base = Typography()

/** Material 3 type scale in Inter: semi-bold, slightly tight headings; regular body. */
val AppTypography = Typography(
    displayLarge = base.displayLarge.inInter(FontWeight.SemiBold, -0.02),
    displayMedium = base.displayMedium.inInter(FontWeight.SemiBold, -0.02),
    displaySmall = base.displaySmall.inInter(FontWeight.SemiBold, -0.02),
    headlineLarge = base.headlineLarge.inInter(FontWeight.SemiBold, -0.02),
    headlineMedium = base.headlineMedium.inInter(FontWeight.SemiBold, -0.02),
    headlineSmall = base.headlineSmall.inInter(FontWeight.SemiBold, -0.015),
    titleLarge = base.titleLarge.inInter(FontWeight.SemiBold, -0.01),
    titleMedium = base.titleMedium.inInter(FontWeight.SemiBold),
    titleSmall = base.titleSmall.inInter(FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.inInter(),
    bodyMedium = base.bodyMedium.inInter(),
    bodySmall = base.bodySmall.inInter(),
    labelLarge = base.labelLarge.inInter(FontWeight.Medium),
    labelMedium = base.labelMedium.inInter(FontWeight.Medium),
    labelSmall = base.labelSmall.inInter(FontWeight.Medium),
)
