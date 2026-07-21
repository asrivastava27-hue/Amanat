package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = SageGreen,
    secondary = InkNavy,
    tertiary = MutedAmber,
    background = InkNavy,
    surface = InkNavy,
    onPrimary = WarmOffWhite,
    onSecondary = WarmOffWhite,
    onBackground = WarmOffWhite,
    onSurface = WarmOffWhite
  )

private val LightColorScheme =
  lightColorScheme(
    primary = SageGreen,
    secondary = InkNavy,
    tertiary = MutedAmber,
    background = WarmOffWhite,
    surface = WarmOffWhite,
    onPrimary = WarmOffWhite,
    onSecondary = InkNavy,
    onBackground = TextDark,
    onSurface = TextDark
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Dynamic color is available on Android 12+
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
