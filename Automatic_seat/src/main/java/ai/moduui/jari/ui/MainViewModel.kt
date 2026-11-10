package ai.moduui.jari.ui

import ai.moduui.jari.AppGraph
import ai.moduui.jari.ModuuiApp
import ai.moduui.jari.control.TripController
import ai.moduui.jari.control.UiState
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val graph: AppGraph = (app as ModuuiApp).graph
    val controller: TripController = graph.controller
    val ui: StateFlow<UiState> = controller.ui

    fun addRestStop(index: Int) {
        viewModelScope.launch { controller.addRestStop(index) }
    }
}

private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")

fun LocalDateTime?.hm(): String = this?.format(HM) ?: "-"
