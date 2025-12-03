package br.edu.utfpr.coletapb.config

/**
 * Configurações centralizadas da API
 * 
 * IMPORTANTE: Altere o IP abaixo para o IP da sua máquina na rede local
 * Para descobrir seu IP:
 * - Windows: ipconfig
 * - Linux/Mac: ifconfig ou ip addr
 * - Ou verifique nas configurações do Android Studio/emulador
 */
object ApiConfig {
    // IP do servidor backend na rede local
    // Altere este valor para o IP da máquina onde o backend está rodando
    const val SERVER_IP = "10.0.2.2"
    const val SERVER_PORT = "8080"
    
    // URL base da API
    const val BASE_URL = "http://$SERVER_IP:$SERVER_PORT/api/v1/"
    
    // Endpoints específicos
    const val LOGIN_ENDPOINT = "${BASE_URL}auth/login"
}

