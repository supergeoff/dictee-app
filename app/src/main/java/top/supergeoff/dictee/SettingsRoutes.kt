package top.supergeoff.dictee

import top.supergeoff.dictee.ui.SettingsPage

/** Correspondances partagées par les accès du menu et ceux des enregistrements. */
internal object SettingsRoutes {
    fun screen(page: SettingsPage): Screen = when (page) {
        SettingsPage.HOME -> Screen.Settings
        SettingsPage.BOX -> Screen.BoxSettings
        SettingsPage.STORAGE -> Screen.StorageSettings
        SettingsPage.TRANSCRIPTION -> Screen.TranscriptionSettings
        SettingsPage.SYNTHESIS -> Screen.SynthesisSettings
    }

    fun screen(destination: SettingsDestination): Screen = when (destination) {
        SettingsDestination.STORAGE -> Screen.StorageSettings
        SettingsDestination.TRANSCRIPTION -> Screen.TranscriptionSettings
        SettingsDestination.SYNTHESIS -> Screen.SynthesisSettings
    }

    fun page(screen: Screen): SettingsPage? = when (screen) {
        Screen.Settings -> SettingsPage.HOME
        Screen.BoxSettings -> SettingsPage.BOX
        Screen.StorageSettings -> SettingsPage.STORAGE
        Screen.TranscriptionSettings -> SettingsPage.TRANSCRIPTION
        Screen.SynthesisSettings -> SettingsPage.SYNTHESIS
        else -> null
    }
}

/** Ignore un rappel provenant d’un écran qui n’est plus au sommet. */
internal fun pushScreen(stack: MutableList<Screen>, from: Screen, next: Screen) {
    if (stack.lastOrNull() == from && from != next) stack += next
}

internal fun popScreen(stack: MutableList<Screen>, from: Screen): Screen? =
    if (stack.size > 1 && stack.last() == from) stack.removeAt(stack.lastIndex) else null
