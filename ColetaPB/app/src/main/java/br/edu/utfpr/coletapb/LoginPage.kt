package br.edu.utfpr.coletapb

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.util.Patterns
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import br.edu.utfpr.coletapb.config.ApiConfig
import br.edu.utfpr.coletapb.data.local.SharedPreferencesHelper
import br.edu.utfpr.coletapb.data.model.LoginRequest
import br.edu.utfpr.coletapb.data.remote.RetrofitClient
import br.edu.utfpr.coletapb.utils.GpsMonitor
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import retrofit2.HttpException

class LoginPage : AppCompatActivity() {
    
    private lateinit var prefsHelper: SharedPreferencesHelper
    private lateinit var gpsMonitor: GpsMonitor
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login_page)
        
        // Inicializa RetrofitClient com contexto
        RetrofitClient.init(this)
        
        prefsHelper = SharedPreferencesHelper(this)
        gpsMonitor = GpsMonitor(this)
        
        // Verifica GPS ao abrir o app - usando post para garantir que a Activity está totalmente criada
        window.decorView.post {
            gpsMonitor.checkAndRequestGps(
                onGpsEnabled = {
                    // GPS está ativo, pode continuar
                    Log.d("LoginPage", "GPS está habilitado, continuando...")
                },
                onGpsDisabled = {
                    // Usuário escolheu sair, app será fechado
                    Log.d("LoginPage", "Usuário escolheu sair, fechando app")
                    finish()
                }
            )
        }
        
        // Não redireciona automaticamente - sempre mostra a tela de login
        // O usuário pode fazer logout se necessário

        val etEmailOrCpf = findViewById<TextInputEditText>(R.id.etCPF)
        val etPassword = findViewById<TextInputEditText>(R.id.etPassword)
        val btLogin = findViewById<Button>(R.id.btLogin)

        btLogin.setOnClickListener {
            val emailOrCpf = etEmailOrCpf.text.toString().trim()
            val password = etPassword.text.toString().trim()

            if (emailOrCpf.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Por favor, preencha todos os campos.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Desabilita o botão durante o login
            btLogin.isEnabled = false
            btLogin.text = "Entrando..."

            lifecycleScope.launch {
                try {
                    val isEmail = Patterns.EMAIL_ADDRESS.matcher(emailOrCpf).matches()
                    val loginRequest = if (isEmail) {
                        LoginRequest(email = emailOrCpf, password = password)
                    } else {
                        LoginRequest(cpf = emailOrCpf, password = password)
                    }

                    val response = RetrofitClient.apiService.login(loginRequest)
                    
                    if (response.isSuccessful && response.body() != null) {
                        val loginResponse = response.body()!!
                        
                        // Salva token e informações do usuário
                        prefsHelper.saveToken(loginResponse.token)
                        prefsHelper.saveUserInfo(
                            email = loginResponse.email,
                            name = loginResponse.name,
                            type = loginResponse.type,
                            userId = loginResponse.userId,
                            driverId = loginResponse.driverId,
                            adminId = loginResponse.adminId
                        )
                        
                        Log.d("LoginPage", "Login bem-sucedido: ${loginResponse.email} (${loginResponse.type})")
                        Log.d("LoginPage", "UserId: ${loginResponse.userId}, DriverId: ${loginResponse.driverId}, AdminId: ${loginResponse.adminId}")
                        
                        // Verifica GPS antes de navegar
                        if (gpsMonitor.isGpsEnabled()) {
                            Toast.makeText(this@LoginPage, "Bem-vindo, ${loginResponse.name}!", Toast.LENGTH_SHORT).show()
                            navigateToTruckList()
                        } else {
                            gpsMonitor.checkAndRequestGps(
                                onGpsEnabled = {
                                    Toast.makeText(this@LoginPage, "Bem-vindo, ${loginResponse.name}!", Toast.LENGTH_SHORT).show()
                                    navigateToTruckList()
                                },
                                onGpsDisabled = {
                                    // GPS não ativado, não navega
                                }
                            )
                        }
                    } else {
                        val errorBody = response.errorBody()?.string() ?: "Erro desconhecido"
                        Log.e("LoginPage", "Erro no login: ${response.code()} - $errorBody")
                        Toast.makeText(this@LoginPage, "Credenciais inválidas. Verifique e tente novamente.", Toast.LENGTH_LONG).show()
                    }
                } catch (e: HttpException) {
                    Log.e("LoginPage", "Erro HTTP: ${e.code()} - ${e.message()}")
                    Toast.makeText(this@LoginPage, "Erro ao conectar com o servidor. Verifique sua conexão.", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Log.e("LoginPage", "Exceção: ${e.message}", e)
                    Toast.makeText(this@LoginPage, "Erro: ${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    // Reabilita o botão
                    btLogin.isEnabled = true
                    btLogin.text = "Entrar"
                }
            }
        }
    }
    
    private fun navigateToTruckList() {
        val intent = Intent(this, TruckList::class.java)
        startActivity(intent)
        finish()
    }
}