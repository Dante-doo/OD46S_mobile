package br.edu.utfpr.coletapb.ui.login

import android.util.Patterns // Necessário para validar e-mail
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.edu.utfpr.coletapb.data.model.LoginResponse
import br.edu.utfpr.coletapb.data.repository.LoginRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed class LoginUiState {
    object Idle : LoginUiState()
    object Loading : LoginUiState()
    data class Success(val data: LoginResponse) : LoginUiState()
    data class Error(val message: String) : LoginUiState()
}

class LoginViewModel(private val repository: LoginRepository) : ViewModel() {

    private val _loginState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val loginState: StateFlow<LoginUiState> = _loginState

    fun login(loginInput: String, password: String) {
        viewModelScope.launch {
            _loginState.value = LoginUiState.Loading

            try {
                val isEmail = Patterns.EMAIL_ADDRESS.matcher(loginInput).matches()

                val email = if (isEmail) loginInput else null
                val cpf = if (!isEmail) loginInput else null

                val response = repository.doLogin(email, cpf, password)

                if (response.isSuccessful && response.body() != null) {
                    _loginState.value = LoginUiState.Success(response.body()!!)
                } else {
                    _loginState.value = LoginUiState.Error("Usuário ou senha inválidos.")
                }
            } catch (e: Exception) {
                _loginState.value = LoginUiState.Error("Falha na conexão. Verifique sua internet.")
            }
        }
    }
}