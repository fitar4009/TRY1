package com.qalaarikha.assistant.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary                = PrimaryLight,
    onPrimary              = OnPrimaryLight,
    primaryContainer       = PrimaryContainerLight,
    onPrimaryContainer     = OnPrimaryContainerLight,
    secondary              = SecondaryLight,
    onSecondary            = OnSecondaryLight,
    secondaryContainer     = SecondaryContainerLight,
    onSecondaryContainer   = OnSecondaryContainerLight,
    tertiary               = TertiaryLight,
    onTertiary             = OnTertiaryLight,
    tertiaryContainer      = TertiaryContainerLight,
    onTertiaryContainer    = OnTertiaryContainerLight,
    error                  = ErrorLight,
    onError                = OnErrorLight,
    errorContainer         = ErrorContainerLight,
    onErrorContainer       = OnErrorContainerLight,
    background             = BackgroundLight,
    onBackground           = OnBackgroundLight,
    surface                = SurfaceLight,
    onSurface              = OnSurfaceLight,
    surfaceVariant         = SurfaceVariantLight,
    onSurfaceVariant       = OnSurfaceVariantLight,
    outline                = OutlineLight,
)

private val DarkColorScheme = darkColorScheme(
    primary                = PrimaryDark,
    onPrimary              = OnPrimaryDark,
    primaryContainer       = PrimaryContainerDark,
    onPrimaryContainer     = OnPrimaryContainerDark,
    secondary              = SecondaryDark,
    onSecondary            = OnSecondaryDark,
    secondaryContainer     = SecondaryContainerDark,
    onSecondaryContainer   = OnSecondaryContainerDark,
    tertiary               = TertiaryDark,
    onTertiary             = OnTertiaryDark,
    tertiaryContainer      = TertiaryContainerDark,
    onTertiaryContainer    = OnTertiaryContainerDark,
    error                  = ErrorDark,
    onError                = OnErrorDark,
    errorContainer         = ErrorContainerDark,
    onErrorContainer       = OnErrorContainerDark,
    background             = BackgroundDark,
    onBackground           = OnBackgroundDark,
    surface                = SurfaceDark,
    onSurface              = OnSurfaceDark,
    surfaceVariant         = SurfaceVariantDark,
    onSurfaceVariant       = OnSurfaceVariantDark,
    outline                = OutlineDark,
)

@Composable
fun QalaArikhaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        // Dynamic color (Android 12+) – disabled so our brand colours are used
        // Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dynamicColor ->
        //     if (darkTheme) dynamicDarkColorScheme(LocalContext.current)
        //     else           dynamicLightColorScheme(LocalContext.current)
        darkTheme -> DarkColorScheme
        else      -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = QalaArikhaTypography,
        content     = content,
    )
}
