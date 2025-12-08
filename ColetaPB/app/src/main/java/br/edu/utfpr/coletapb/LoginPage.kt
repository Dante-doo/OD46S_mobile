package br.edu.utfpr.coletapb

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import br.edu.utfpr.coletapb.data.local.SessionManager
import br.edu.utfpr.coletapb.ui.login.LoginUiState
import br.edu.utfpr.coletapb.ui.login.LoginViewModel
import br.edu.utfpr.coletapb.ui.login.LoginViewModelFactory
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class LoginPage : AppCompatActivity() {

    private val viewModel: LoginViewModel by viewModels {
        LoginViewModelFactory(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login_page)

        val etEmailOrCpf = findViewById<TextInputEditText>(R.id.etCPF)
        val etPassword = findViewById<TextInputEditText>(R.id.etPassword)
        val btLogin = findViewById<Button>(R.id.btLogin)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.loginState.collect { state ->
                    when (state) {
                        is LoginUiState.Loading -> {
                            btLogin.isEnabled = false
                            btLogin.text = "Aguarde..."
                        }
                        is LoginUiState.Success -> {
                            btLogin.isEnabled = true
                            btLogin.text = "Entrar"

                            val token = state.data.token
                            if (!token.isNullOrEmpty()) {
                                SessionManager.saveToken(this@LoginPage, token)

                                Toast.makeText(this@LoginPage, "Login OK!", Toast.LENGTH_SHORT).show()

                                val intent = Intent(this@LoginPage, CurrentAssignmentList::class.java)
                                startActivity(intent)
                                finish()
                            } else {
                                Toast.makeText(this@LoginPage, "Erro: Token vazio", Toast.LENGTH_SHORT).show()
                            }
                        }
                        is LoginUiState.Error -> {
                            btLogin.isEnabled = true
                            btLogin.text = "Entrar"
                            Toast.makeText(this@LoginPage, state.message, Toast.LENGTH_LONG).show()
                        }
                        is LoginUiState.Idle -> {
                            btLogin.isEnabled = true
                            btLogin.text = "Entrar"
                        }
                    }
                }
            }
        }

        btLogin.setOnClickListener {
            val emailOrCpf = etEmailOrCpf.text.toString().trim()
            val password = etPassword.text.toString().trim()

            if (emailOrCpf.isNotEmpty() && password.isNotEmpty()) {
                viewModel.login(emailOrCpf, password)
            } else {
                Toast.makeText(this, "Preencha todos os campos", Toast.LENGTH_SHORT).show()
            }
        }
    }
}