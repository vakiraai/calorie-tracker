package com.liftcal.app.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.liftcal.app.LiftCalApp
import com.liftcal.app.ui.screens.CalibrationScreen
import com.liftcal.app.ui.screens.ConnectionsScreen
import com.liftcal.app.ui.screens.HomeScreen
import com.liftcal.app.ui.screens.ProfileScreen
import com.liftcal.app.ui.screens.SummaryScreen
import com.liftcal.app.ui.screens.WorkoutScreen

object Routes {
    const val HOME = "home"
    const val PROFILE = "profile"
    const val CONNECTIONS = "connections"
    const val CALIBRATION = "calibration"
    const val WORKOUT = "workout/{id}"
    const val SUMMARY = "summary/{id}"
    fun workout(id: Long) = "workout/$id"
    fun summary(id: Long) = "summary/$id"
}

fun CreationExtras.app(): LiftCalApp = this[APPLICATION_KEY] as LiftCalApp

inline fun <reified VM : ViewModel> factory(crossinline create: CreationExtras.() -> VM) =
    viewModelFactory { initializer { create() } }

@Composable
fun LiftCalNavHost() {
    val nav = rememberNavController()
    val idArg = listOf(navArgument("id") { type = NavType.LongType })
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onStartWorkout = { nav.navigate(Routes.workout(it)) },
                onOpenWorkout = { id, active -> nav.navigate(if (active) Routes.workout(id) else Routes.summary(id)) },
                onProfile = { nav.navigate(Routes.PROFILE) },
                onConnections = { nav.navigate(Routes.CONNECTIONS) },
                onCalibration = { nav.navigate(Routes.CALIBRATION) },
            )
        }
        composable(Routes.PROFILE) { ProfileScreen(onDone = { nav.popBackStack() }) }
        composable(Routes.CONNECTIONS) { ConnectionsScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.CALIBRATION) {
            CalibrationScreen(onBack = { nav.popBackStack() }, onProfile = { nav.navigate(Routes.PROFILE) })
        }
        composable(Routes.WORKOUT, idArg) { entry ->
            val id = entry.arguments!!.getLong("id")
            WorkoutScreen(
                workoutId = id,
                onBack = { nav.popBackStack() },
                onFinished = {
                    nav.navigate(Routes.summary(id)) { popUpTo(Routes.HOME) }
                },
            )
        }
        composable(Routes.SUMMARY, idArg) { entry ->
            SummaryScreen(workoutId = entry.arguments!!.getLong("id"), onBack = { nav.popBackStack() })
        }
    }
}
