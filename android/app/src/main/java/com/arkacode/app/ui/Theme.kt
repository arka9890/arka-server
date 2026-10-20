package com.arkacode.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/**
 * Claymorphism Arkacode — biru muda.
 *
 * Bahasa desain: permukaan puffy bersudut besar, bayangan lembut bertinta biru,
 * gradien vertikal halus (highlight di atas, base di bawah).
 * Terang = putih di atas kanvas biru muda. Gelap = navy laut dalam.
 */

// ---- Palet terang (biru muda) ----
private val BlueAccent = Color(0xFF5B9BE0)
private val BlueAccentDeep = Color(0xFF143A5C)
private val BluePale = Color(0xFFD6E8FB)
private val BgLight = Color(0xFFEAF4FF)
private val SurfaceLight = Color(0xFFF5FAFF)
private val CardLight = Color(0xFFFFFFFF)
private val InkLight = Color(0xFF27415F)
private val InkSecondaryLight = Color(0xFF566B85)

// ---- Palet gelap (navy laut dalam) ----
private val BlueNight = Color(0xFF8FC3FF)
private val BgNight = Color(0xFF0D1724)
private val SurfaceNight = Color(0xFF121D2C)
private val CardNight = Color(0xFF17263A)
private val InkNight = Color(0xFFDCE8F6)

private val ClayLight = lightColorScheme(
    primary = BlueAccent,
    onPrimary = Color.White,
    primaryContainer = BluePale,
    onPrimaryContainer = BlueAccentDeep,
    secondary = Color(0xFF4E86C6),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE1EEFB),
    onSecondaryContainer = BlueAccentDeep,
    tertiary = Color(0xFF2FA37C),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD3F2E3),
    onTertiaryContainer = Color(0xFF0B3D28),
    background = BgLight,
    onBackground = InkLight,
    surface = SurfaceLight,
    onSurface = InkLight,
    surfaceVariant = Color(0xFFDCEBFC),
    onSurfaceVariant = InkSecondaryLight,
    surfaceContainerHigh = CardLight,
    surfaceContainerHighest = Color(0xFFE3EFFB),
    outline = Color(0xFFC2DCF4),
    outlineVariant = Color(0xFFD8E8F8),
    error = Color(0xFFD9636B),
    onError = Color.White,
    errorContainer = Color(0xFFFBDDDF),
    onErrorContainer = Color(0xFF5A1A1E),
)

private val ClayDark = darkColorScheme(
    primary = BlueNight,
    onPrimary = Color(0xFF06283F),
    primaryContainer = Color(0xFF1D4266),
    onPrimaryContainer = Color(0xFFD6EBFF),
    secondary = Color(0xFF9CC0E6),
    onSecondary = Color(0xFF06283F),
    secondaryContainer = Color(0xFF23405E),
    onSecondaryContainer = Color(0xFFD6EBFF),
    tertiary = Color(0xFF5FCE9E),
    onTertiary = Color(0xFF06281A),
    tertiaryContainer = Color(0xFF14352A),
    onTertiaryContainer = Color(0xFFB9F0D8),
    background = BgNight,
    onBackground = InkNight,
    surface = SurfaceNight,
    onSurface = InkNight,
    surfaceVariant = Color(0xFF22334A),
    onSurfaceVariant = Color(0xFFA9C2DA),
    surfaceContainerHigh = CardNight,
    surfaceContainerHighest = Color(0xFF22334A),
    outline = Color(0xFF2A3C52),
    outlineVariant = Color(0xFF20303F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun ArkacodeTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) ClayDark else ClayLight,
    ) {
        // Surface akar: tanpa ini, layar yang tak mengecat background sendiri
        // (Ready/Boot/Stopped) memperlihatkan window background tema Light —
        // kanvas tetap terang walau mode gelap.
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            content()
        }
    }
}

/** Bentuk clay standar: sudut besar. */
val ClayShape: Shape = RoundedCornerShape(22.dp)

/** Bentuk clay kecil (badge/pill). */
val ClayShapeSmall: Shape = RoundedCornerShape(12.dp)

/** Bentuk clay besar untuk permukaan utama / bottom bar. */
val ClayShapeLarge: Shape = RoundedCornerShape(28.dp)

/** Bentuk pill penuh. */
val ClayShapePill: Shape = RoundedCornerShape(percent = 50)

/** True bila skema warna sedang gelap. */
@Composable
fun isClayDark(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

/** Gradien permukaan clay: highlight di atas → base di bawah. */
@Composable
fun claySurfaceBrush(): Brush {
    val card = MaterialTheme.colorScheme.surfaceContainerHigh
    val dark = isClayDark()
    val bottom = if (dark) {
        lerp(card, Color.Black, 0.22f)
    } else {
        lerp(card, MaterialTheme.colorScheme.surfaceVariant, 0.40f)
    }
    return Brush.verticalGradient(listOf(card, bottom))
}

/**
 * Kartu clay seragam: sudut besar + bayangan lembut bertinta primary
 * (terang) / dalam (gelap) + gradien puffy.
 * Pakai [contentPadding] = PaddingValues(0.dp) bila isi kartu sudah punya
 * padding sendiri.
 */
@Composable
fun ClayCard(
    modifier: Modifier = Modifier,
    shape: Shape = ClayShape,
    contentPadding: PaddingValues = PaddingValues(14.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val dark = isClayDark()
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Column(
            modifier
                .shadow(
                    elevation = if (dark) 12.dp else 14.dp,
                    shape = shape,
                    ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = if (dark) 0.10f else 0.14f),
                    spotColor = MaterialTheme.colorScheme.primary.copy(alpha = if (dark) 0.22f else 0.28f),
                )
                .clip(shape)
                .background(claySurfaceBrush())
                .padding(contentPadding),
            content = content,
        )
    }
}
