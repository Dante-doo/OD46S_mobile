package br.edu.utfpr.coletapb

import android.content.Intent // IMPORT NECESSÁRIO
import android.os.Bundle
import android.util.Log
import android.util.Patterns
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
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
        setContentView(R.layout.activity_login_page)

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

            val jsonObject = JSONObject()
            val isEmail = Patterns.EMAIL_ADDRESS.matcher(emailOrCpf).matches()
            if (isEmail) {
                jsonObject.put("email", emailOrCpf)
            } else {
                jsonObject.put("cpf", emailOrCpf)
            }
            jsonObject.put("password", password)

            thread {
                var connection: HttpURLConnection? = null
                try {
                    // Use o IP da sua máquina na rede local
                    val url = URL("http://192.168.250.152:8080/api/v1/auth/login")
                    connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("Content-Type", "application/json; utf-8")
                    connection.setRequestProperty("Accept", "application/json")
                    connection.doOutput = true

                    OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
                        writer.write(jsonObject.toString())
                        writer.flush()
                    }

                    val responseCode = connection.responseCode
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val response = reader.readText()

                    runOnUiThread {
                        if (responseCode == HttpURLConnection.HTTP_OK) {
                            Toast.makeText(this, "Login bem-sucedido!", Toast.LENGTH_LONG).show()
                            Log.d("LoginPage", "Resposta da API: $response")

                            // --- NAVEGAÇÃO PARA A TELA DE LISTA DE CAMINHÕES ---
                            val intent = Intent(this, TruckList::class.java)

                            startActivity(intent)

                            finish()
                        } else {
                            Toast.makeText(this, "Falha no login: $responseCode", Toast.LENGTH_LONG).show()
                            Log.e("LoginPage", "Erro ($responseCode): $response")
                        }
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                    runOnUiThread {
                        Toast.makeText(this, "Erro: ${e.message}", Toast.LENGTH_LONG).show()
                        Log.e("LoginPage", "Exceção: ${e.message}")
                    }
                } finally {
                    connection?.disconnect()
                }
            }
        }
    }
}