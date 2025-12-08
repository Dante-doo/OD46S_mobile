package br.edu.utfpr.coletapb.ui.login

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import br.edu.utfpr.coletapb.data.repository.LoginRepository
import br.edu.utfpr.coletapb.data.remote.RetrofitClient

// Agora a Factory recebe o Contexto como parâmetro
class LoginViewModelFactory(private val context: Context) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LoginViewModel::class.java)) {
            return LoginViewModel(
                repository = LoginRepository(
                    // Passa o contexto para obter o ApiService com o Token injetado
                    apiService = RetrofitClient.getApiService(context)
                )
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}