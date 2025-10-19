package br.edu.utfpr.coletapb.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.edu.utfpr.coletapb.data.model.LoginResponse
import br.edu.utfpr.coletapb.data.repository.LoginRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// Define os possíveis estados da tela de Login
sealed class LoginUiState {
    object Idle : LoginUiState() // Estado inicial
    object Loading : LoginUiState() // Carregando
    data class Success(val data: LoginResponse) : LoginUiState() // Sucesso
    data class Error(val message: String) : LoginUiState() // Erro
}

class LoginViewModel(private val repository: LoginRepository) : ViewModel() {

    private val _loginState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val loginState: StateFlow<LoginUiState> = _loginState

    fun login(cpf: String, password: String) {
        // Usa o escopo do ViewModel para lançar a coroutine
        viewModelScope.launch {
            // 1. Emite o estado de Loading
            _loginState.value = LoginUiState.Loading

            try {
                val response = repository.doLogin(cpf, password)
                if (response.isSuccessful && response.body() != null) {
                    // 2. Em caso de sucesso, emite o estado de Success
                    _loginState.value = LoginUiState.Success(response.body()!!)
                } else {
                    // 3. Em caso de erro da API (ex: 401, 404), emite o estado de Error
                    _loginState.value = LoginUiState.Error("CPF ou senha inválidos.")
                }
            } catch (e: Exception) {
                // 4. Em caso de erro de conexão/inesperado, emite o estado de Error
                _loginState.value = LoginUiState.Error("Falha na conexão. Tente novamente.")
            }
        }
    }
}