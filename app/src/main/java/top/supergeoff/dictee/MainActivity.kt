package top.supergeoff.dictee

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import top.supergeoff.dictee.ui.AssociateSheet
import top.supergeoff.dictee.ui.Dictee
import top.supergeoff.dictee.ui.DicteeTheme
import top.supergeoff.dictee.ui.DriveFolderScreen
import top.supergeoff.dictee.ui.JournalScreen
import top.supergeoff.dictee.ui.PrimaryButton
import top.supergeoff.dictee.ui.RecordingScreen
import top.supergeoff.dictee.ui.RecordingsScreen
import top.supergeoff.dictee.ui.SettingsScreen
import top.supergeoff.dictee.ui.WelcomeScreen

/** Écrans de l'app : [Home] est la racine de la pile, les autres s'empilent dessus. */
sealed class Screen {
    data object Home : Screen()
    data class Detail(val name: String) : Screen()
    data object Settings : Screen()
    data object DriveFolders : Screen()
    data object Journal : Screen()
}

/**
 * L'activité unique : la pile d'écrans, le thème, et tout ce qui parle au système. Les écrans n'affichent que [AppModel.ui] et
 * appellent ses actions ; ce que le modèle demande au système (permissions, dossier, accord Google) arrive par
 * [AppModel.requests], collecté pendant toute la vie de l'activité pour qu'une demande émise à l'arrêt ne soit pas perdue.
 */
class MainActivity : ComponentActivity() {
    private val model: AppModel by lazy { (application as DicteeApp).model }
    private val stack = mutableStateListOf<Screen>(Screen.Home)
    private var associating by mutableStateOf(false)
    private var searchAfterPermission = false

    private val bluetoothPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        onBluetoothResult(result.isNotEmpty() && result.values.all { it })
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val phoneFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        model.onPhoneFolderPicked(uri)
    }
    private val googleConsent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        model.onGoogleResult(result.data.takeIf { result.resultCode == Activity.RESULT_OK })
    }
    private val googlePicker = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        model.onPickerResult(result.data.takeIf { result.resultCode == Activity.RESULT_OK })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch { model.requests.collect { handle(it) } }
        setContent { Root() }
    }

    override fun onStart() {
        super.onStart()
        model.onForeground()
    }

    override fun onStop() {
        model.onBackground()
        super.onStop()
    }

    private fun handle(request: UiRequest) {
        when (request) {
            UiRequest.BluetoothPermission -> bluetoothPermissions.launch(BLUETOOTH_PERMISSIONS)
            UiRequest.NotificationPermission ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            UiRequest.PickPhoneFolder -> phoneFolder.launch(PhoneFolder.initialUri)
            is UiRequest.GoogleConsent -> googleConsent.launch(IntentSenderRequest.Builder(request.intent).build())
            is UiRequest.GooglePicker -> googlePicker.launch(IntentSenderRequest.Builder(request.intent).build())
        }
    }

    /** Ouvre la feuille de recherche, après la permission Bluetooth si elle manque. */
    private fun startSearch() {
        if (hasBluetooth()) {
            associating = true
        } else {
            searchAfterPermission = true
            model.connectBox()
        }
    }

    private fun onBluetoothResult(granted: Boolean) {
        model.onBluetoothPermission(granted)
        if (searchAfterPermission) {
            searchAfterPermission = false
            if (granted) associating = true
        }
    }

    private fun hasBluetooth(): Boolean =
        BLUETOOTH_PERMISSIONS.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    @Composable
    private fun Root() {
        val ui by model.ui.collectAsState()
        val dark = when (ui.local.theme) {
            ThemeChoice.LIGHT -> false
            ThemeChoice.DARK -> true
            ThemeChoice.SYSTEM -> isSystemInDarkTheme()
        }
        SystemBars(dark)
        LaunchedEffect(ui.message) {
            val message = ui.message ?: return@LaunchedEffect
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            model.consumeMessage()
        }
        DicteeTheme(dark) {
            Box(modifier = Modifier.fillMaxSize().background(Dictee.colors.background)) {
                if (ui.initialized) Navigation(ui, model, stack, onSearch = ::startSearch)
                else InitializationScreen(ui.initializationError, model::retryLocalState)
                if (ui.initialized && associating) {
                    // Quelle que soit la sortie de la feuille, y compris sa disparition avec l'activité, le modèle peut rouvrir la liaison.
                    DisposableEffect(Unit) { onDispose { model.endSearch() } }
                    AssociateSheet(
                        beforeSearch = model::closeForSearch,
                        onAssociate = { name, address ->
                            model.associate(name, address)
                            associating = false
                        },
                        onDismiss = {
                            model.cancelSearch()
                            associating = false
                        },
                    )
                }
            }
        }
    }

    /** Icônes des barres système selon le thème de l'app, qui peut différer de celui du système. Le bord à bord est posé dans [onCreate]. */
    @Composable
    private fun SystemBars(dark: Boolean) {
        DisposableEffect(dark) {
            val bars = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
            enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            onDispose {}
        }
    }

    private companion object {
        val BLUETOOTH_PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    }
}

@Composable
private fun InitializationScreen(error: String?, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (error == null) {
            CircularProgressIndicator(color = Dictee.colors.primary)
            Text("Chargement des données locales…", modifier = Modifier.padding(top = 16.dp),
                style = Dictee.type.body, color = Dictee.colors.text)
        } else {
            Text("Impossible de charger les données locales", style = Dictee.type.titleSection,
                color = Dictee.colors.destructive, textAlign = TextAlign.Center)
            Text(error, modifier = Modifier.padding(top = 12.dp), style = Dictee.type.body,
                color = Dictee.colors.text, textAlign = TextAlign.Center)
            Text("Les données existantes ne sont pas remplacées. Vérifie leur accès avant de réessayer.",
                modifier = Modifier.padding(top = 12.dp), style = Dictee.type.body,
                color = Dictee.colors.textSecondary, textAlign = TextAlign.Center)
            PrimaryButton(text = "Réessayer", modifier = Modifier.padding(top = 20.dp), onClick = onRetry)
        }
    }
}

/**
 * Affiche l'écran du haut de la pile ; le retour le dépile. L'état d'un écran quitté (filtre, position de la liste) est gardé
 * le temps qu'il reste dans la pile, et oublié quand l'écran est dépilé : la racine, jamais dépilée, garde le sien.
 * Seul l'écran du haut empile ou dépile : un second toucher, avant que l'écran n'ait changé, ne fait rien.
 */
@Composable
private fun Navigation(ui: UiState, model: AppModel, stack: SnapshotStateList<Screen>, onSearch: () -> Unit) {
    val saveable = rememberSaveableStateHolder()
    val screen = stack.last()
    val push: (Screen) -> Unit = { next -> if (stack.last() == screen && screen != next) stack += next }
    val back: () -> Unit = {
        if (stack.size > 1 && stack.last() == screen) {
            val closed = stack.removeAt(stack.lastIndex)
            saveable.removeState(closed.toString())
        }
    }
    BackHandler(enabled = stack.size > 1, onBack = back)
    saveable.SaveableStateProvider(screen.toString()) {
        when (screen) {
            Screen.Home ->
                if (ui.local.deviceAddress == null) {
                    WelcomeScreen(ui, onSearch = onSearch, onSettings = { push(Screen.Settings) })
                } else {
                    RecordingsScreen(ui, model, onOpen = { push(Screen.Detail(it)) }, onSettings = { push(Screen.Settings) })
                }
            is Screen.Detail -> RecordingScreen(ui, screen.name, model, back)
            Screen.Settings -> SettingsScreen(
                ui = ui,
                model = model,
                onBack = back,
                onAssociate = onSearch,
                onDriveFolders = { push(Screen.DriveFolders) },
                onJournal = { push(Screen.Journal) },
            )
            Screen.DriveFolders -> DriveFolderScreen(ui, model, back)
            Screen.Journal -> JournalScreen(model, back)
        }
    }
}
