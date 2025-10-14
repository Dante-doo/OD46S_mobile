package br.edu.utfpr.coletapb

import android.os.Bundle
import android.util.Log
import android.util.Patterns
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class LoginPage : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_login_page)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Referências para os componentes da UI
        val etEmailOrCpf = findViewById<EditText>(R.id.etCPF)
        val etPassword = findViewById<EditText>(R.id.etPassword)
        val btLogin = findViewById<Button>(R.id.btLogin)

        // Ação do botão de login
        btLogin.setOnClickListener {
            Toast.makeText(this, "Botão de login clicado!", Toast.LENGTH_SHORT).show();
            val emailOrCpf = etEmailOrCpf.text.toString().trim()
            val password = etPassword.text.toString().trim()

            // Validação simples dos campos
            if (emailOrCpf.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Por favor, preencha todos os campos.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Cria o objeto JSON para enviar na requisição
            val jsonObject = JSONObject()

            // Verifica se o campo de entrada é um email válido
            val isEmail = Patterns.EMAIL_ADDRESS.matcher(emailOrCpf).matches()

            if (isEmail) {
                jsonObject.put("email", emailOrCpf)
            } else {
                // Assume que é um CPF se não for um e-mail
                jsonObject.put("cpf", emailOrCpf)
            }
            jsonObject.put("password", password)

            // Executa a chamada de rede em uma thread separada
            thread {
                try {
                    val url = URL("http://192.168.250.152:8080/api/v1/auth/login") // Use o URL de desenvolvimento da API
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("Content-Type", "application/json; utf-8")
                    connection.setRequestProperty("Accept", "application/json")
                    connection.doOutput = true

                    // Envia os dados do JSON
                    OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
                        writer.write(jsonObject.toString())
                        writer.flush()
                    }

                    val responseCode = connection.responseCode

                    // Lê a resposta da API
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val response = reader.readText()

                    runOnUiThread {
                        if (responseCode == HttpURLConnection.HTTP_OK) {
                            // Sucesso! Processar a resposta e o token JWT.
                            Toast.makeText(this, "Login bem-sucedido!", Toast.LENGTH_LONG).show()
                            Log.d("LoginPage", "Resposta da API: $response")
                            // Aqui você extrairia o token e navegaria para a próxima tela
                        } else {
                            // Trata o erro
                            Toast.makeText(this, "Falha no login: $responseCode", Toast.LENGTH_LONG).show()
                            Log.e("LoginPage", "Erro ($responseCode): $response")
                        }
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                    runOnUiThread {
                        Toast.makeText(this, "Erro de conexão. Verifique sua rede.", Toast.LENGTH_LONG).show()
                        Log.e("LoginPage", "Exceção: ${e.message}")
                    }
                }
            }
        }
    }
}