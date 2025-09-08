package br.edu.utfpr.coletapb

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import br.edu.utfpr.coletapb.data.remote.RetrofitClient
import br.edu.utfpr.coletapb.data.repository.LoginRepository
import br.edu.utfpr.coletapb.ui.login.LoginUiState
import br.edu.utfpr.coletapb.ui.login.LoginViewModel
import kotlinx.coroutines.launch

class LoginPage : AppCompatActivity() {
    private lateinit var etCPF: EditText
    private lateinit var etPassword: EditText
    private lateinit var btLogin: Button
    private lateinit var progressBar: ProgressBar // Adicione uma ProgressBar no seu XML

    // Inicializa o ViewModel
    private val loginViewModel: LoginViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                // Configuração simples para criar o ViewModel com suas dependências
                // O ideal aqui seria usar Injeção de Dependência (Hilt, Koin)
                val repository = LoginRepository(RetrofitClient.apiService)
                return LoginViewModel(repository) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login_page)

        etCPF = findViewById(R.id.etCPF)
        etPassword = findViewById(R.id.etPassword)
        btLogin = findViewById(R.id.btLogin)
//        progressBar = findViewById(R.id.progressBar) // Associe à sua ProgressBar no layout

        btLogin.setOnClickListener {
            login()
        }

        observeLoginState()
    }

    private fun login() {
        val cpf = etCPF.text.toString().trim()
        val password = etPassword.text.toString().trim()

        if (cpf.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Preencha todos os campos", Toast.LENGTH_SHORT).show()
            return
        }

        // A Activity agora só delega a ação para o ViewModel
        loginViewModel.login(cpf, password)
    }

    private fun observeLoginState() {
        lifecycleScope.launch {
            loginViewModel.loginState.collect { state ->
                when (state) {
                    is LoginUiState.Loading -> {
                        // Mostra a barra de progresso e desabilita o botão
                        progressBar.visibility = View.VISIBLE
                        btLogin.isEnabled = false
                    }
                    is LoginUiState.Success -> {
                        // Esconde a barra de progresso e navega para a próxima tela
                        progressBar.visibility = View.GONE
                        btLogin.isEnabled = true
                        Toast.makeText(this@LoginPage, "Login bem-sucedido! Bem-vindo, ${state.data.userName}", Toast.LENGTH_LONG).show()
                        // Ex: Iniciar a MainActivity
                        // val intent = Intent(this@LoginPage, MainActivity::class.java)
                        // startActivity(intent)
                        // finish()
                    }
                    is LoginUiState.Error -> {
                        // Esconde a barra de progresso, habilita o botão e mostra o erro
                        progressBar.visibility = View.GONE
                        btLogin.isEnabled = true
                        Toast.makeText(this@LoginPage, state.message, Toast.LENGTH_LONG).show()
                    }
                    is LoginUiState.Idle -> {
                        // Estado inicial, tudo visível e habilitado
                        progressBar.visibility = View.GONE
                        btLogin.isEnabled = true
                    }
                }
            }
        }
    }
}