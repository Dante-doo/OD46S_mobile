package br.edu.utfpr.coletapb.ui.route

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import br.edu.utfpr.coletapb.data.model.RouteEntity // Importação correta
import br.edu.utfpr.coletapb.data.repository.RouteRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.lang.IllegalArgumentException

sealed class RouteUiState {
    object Idle : RouteUiState()
    object Loading : RouteUiState()
    data class Success(val routes: List<RouteEntity>) : RouteUiState()
    data class Error(val message: String) : RouteUiState()
}

class RouteViewModel(private val repository: RouteRepository) : ViewModel() {

    private val _routeState = MutableStateFlow<RouteUiState>(RouteUiState.Idle)
    val routeState: StateFlow<RouteUiState> = _routeState

    fun loadRoutes(driverId: Long, truckId: Long) {
        viewModelScope.launch {
            _routeState.value = RouteUiState.Loading
            try {
                // O repositório retorna Response<RouteListResponse>
                val response = repository.getRoutes(driverId, truckId)

                if (response.isSuccessful && response.body() != null) {
                    val apiData = response.body()!!.data

                    // Converte os DTOs da API para as Entidades usadas na UI/Banco
                    val routeEntities = apiData.routes.map { dto ->
                        RouteEntity(
                            id = dto.id,
                            name = dto.name,
                            description = dto.description,
                            collection_type = dto.collection_type ?: "NORMAL",
                            periodicity = dto.periodicity,
                            priority = dto.priority ?: "MEDIUM",
                            estimated_time_minutes = dto.estimated_time_minutes ?: 0,
                            distance_km = dto.distance_km ?: 0.0
                        )
                    }

                    _routeState.value = RouteUiState.Success(routeEntities)
                } else {
                    _routeState.value = RouteUiState.Error("Falha ao buscar rotas: ${response.message()}")
                }
            } catch (e: Exception) {
                _routeState.value = RouteUiState.Error("Erro de conexão: ${e.message}")
            }
        }
    }
}

class RouteViewModelFactory(private val repository: RouteRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RouteViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return RouteViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}