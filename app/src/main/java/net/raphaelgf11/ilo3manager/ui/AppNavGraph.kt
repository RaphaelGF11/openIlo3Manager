package net.raphaelgf11.ilo3manager.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.raphaelgf11.ilo3manager.ui.update.UpdateAvailableDialog
import net.raphaelgf11.ilo3manager.update.AvailableUpdate
import net.raphaelgf11.ilo3manager.update.UpdateChecker
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import net.raphaelgf11.ilo3manager.data.HostRepository
import net.raphaelgf11.ilo3manager.data.NotificationSettingsRepository
import net.raphaelgf11.ilo3manager.data.SettingsRepository
import net.raphaelgf11.ilo3manager.ui.host.AddEditHostScreen
import net.raphaelgf11.ilo3manager.ui.host.HostListScreen
import net.raphaelgf11.ilo3manager.ui.host.HostListViewModel
import net.raphaelgf11.ilo3manager.ui.hostdetail.HostDetailScreen
import net.raphaelgf11.ilo3manager.ui.settings.SettingsScreen

private const val ROUTE_HOST_LIST = "hosts"
private const val ROUTE_ADD_HOST = "hosts/add"
private const val ROUTE_EDIT_HOST = "hosts/{hostId}/edit"
private const val ROUTE_HOST_DETAIL = "hosts/{hostId}"
private const val ROUTE_SETTINGS = "settings"

@Composable
fun AppNavGraph(
    repository: HostRepository,
    settingsRepository: SettingsRepository,
    notificationSettingsRepository: NotificationSettingsRepository,
    initialHostId: String? = null,
) {
    val navController = rememberNavController()
    val context = androidx.compose.ui.platform.LocalContext.current

    // Opened from a front-panel widget: go straight to that server, with the host list left
    // underneath so Back returns there rather than out of the app.
    LaunchedEffect(initialHostId) {
        val host = initialHostId?.let { id -> repository.getHosts().firstOrNull { it.id == id } }
        if (host != null) navController.navigate("hosts/${host.id}")
    }

    // Checked once per launch, and only if the prompt is enabled: the settings screen remains the
    // place where updates are actually applied.
    var pendingUpdate by remember { mutableStateOf<AvailableUpdate?>(null) }
    LaunchedEffect(Unit) {
        if (!settingsRepository.updateDialogEnabled) return@LaunchedEffect
        pendingUpdate = withContext(Dispatchers.IO) {
            runCatching { UpdateChecker.check(context) }.getOrNull()
        }
    }

    pendingUpdate?.let { update ->
        UpdateAvailableDialog(
            update = update,
            onLater = { pendingUpdate = null },
            onNeverAsk = {
                settingsRepository.updateDialogEnabled = false
                pendingUpdate = null
            },
            onSeeMore = {
                pendingUpdate = null
                navController.navigate(ROUTE_SETTINGS)
            },
        )
    }

    NavHost(
        navController = navController,
        startDestination = ROUTE_HOST_LIST,
        enterTransition = { slideInHorizontally(initialOffsetX = { fullWidth -> fullWidth }) },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { slideOutHorizontally(targetOffsetX = { fullWidth -> fullWidth }) },
    ) {
        composable(ROUTE_HOST_LIST) {
            val viewModel: HostListViewModel = viewModel(
                factory = viewModelFactory { initializer { HostListViewModel(repository) } },
            )
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            HostListScreen(
                viewModel = viewModel,
                notificationSettingsRepository = notificationSettingsRepository,
                onAddHost = { navController.navigate(ROUTE_ADD_HOST) },
                onOpenHost = { host -> navController.navigate("hosts/${host.id}") },
                onEditHost = { host -> navController.navigate("hosts/${host.id}/edit") },
                onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
            )
        }
        composable(ROUTE_SETTINGS) {
            SettingsScreen(
                repository = settingsRepository,
                hostRepository = repository,
                onBack = { navController.popBackStack() },
            )
        }
        composable(ROUTE_ADD_HOST) {
            AddEditHostScreen(
                repository = repository,
                existingHost = null,
                onDone = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            ROUTE_EDIT_HOST,
            arguments = listOf(navArgument("hostId") { type = NavType.StringType }),
        ) { entry ->
            val hostId = entry.arguments?.getString("hostId")
            val host = repository.getHosts().firstOrNull { it.id == hostId }
            AddEditHostScreen(
                repository = repository,
                existingHost = host,
                onDone = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            ROUTE_HOST_DETAIL,
            arguments = listOf(navArgument("hostId") { type = NavType.StringType }),
        ) { entry ->
            val hostId = entry.arguments?.getString("hostId")
            val host = repository.getHosts().firstOrNull { it.id == hostId }
            if (host != null) {
                HostDetailScreen(
                    initialHost = host,
                    settingsRepository = settingsRepository,
                    hostRepository = repository,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
