package br.edu.utfpr.coletapb.ui.assignment

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import br.edu.utfpr.coletapb.data.local.SharedPreferencesHelper
import br.edu.utfpr.coletapb.data.model.Assignment
import br.edu.utfpr.coletapb.data.repository.AssignmentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class AssignmentUiState {
    object Idle : AssignmentUiState()
    object Loading : AssignmentUiState()
    data class Success(val assignments: List<Assignment>) : AssignmentUiState()
    data class Error(val message: String) : AssignmentUiState()
}

class AssignmentViewModel(application: Application) : AndroidViewModel(application) {
    
    private val prefsHelper = SharedPreferencesHelper(application)
    private val repository = AssignmentRepository(prefsHelper)
    
    private val _uiState = MutableStateFlow<AssignmentUiState>(AssignmentUiState.Idle)
    val uiState: StateFlow<AssignmentUiState> = _uiState.asStateFlow()
    
    private val _currentAssignment = MutableStateFlow<Assignment?>(null)
    val currentAssignment: StateFlow<Assignment?> = _currentAssignment.asStateFlow()
    
    fun loadAssignments() {
        viewModelScope.launch {
            _uiState.value = AssignmentUiState.Loading
            
            repository.getMyAssignments()
                .onSuccess { assignments ->
                    _uiState.value = AssignmentUiState.Success(assignments)
                    Log.d("AssignmentViewModel", "Carregadas ${assignments.size} escalas")
                }
                .onFailure { error ->
                    _uiState.value = AssignmentUiState.Error(error.message ?: "Erro desconhecido")
                    Log.e("AssignmentViewModel", "Erro ao carregar escalas: ${error.message}")
                }
        }
    }
    
    fun loadCurrentAssignment() {
        viewModelScope.launch {
            repository.getMyCurrentAssignment()
                .onSuccess { assignment ->
                    _currentAssignment.value = assignment
                    Log.d("AssignmentViewModel", "Assignment atual: ${assignment?.routeName ?: "nenhum"}")
                }
                .onFailure { error ->
                    Log.e("AssignmentViewModel", "Erro ao carregar assignment atual: ${error.message}")
                }
        }
    }
}

