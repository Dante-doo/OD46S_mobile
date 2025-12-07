package br.edu.utfpr.coletapb.data.model

/**
 * Enum que representa os tipos de eventos GPS
 * Corresponde ao enum GPSEventType do backend
 */
enum class GpsEventType(val apiValue: String, val description: String) {
    // Eventos de Percurso
    START("START", "Início da Coleta"),
    NORMAL("NORMAL", "Percurso Normal"),
    STOP("STOP", "Parada"),
    BREAK("BREAK", "Intervalo/Descanso"),
    
    // Eventos de Coleta em Pontos
    POINT_COLLECTED("POINT_COLLECTED", "Ponto Coletado com Sucesso"),
    
    // Eventos Gerais
    PROBLEM("PROBLEM", "Problema Geral"),
    END("END", "Fim da Coleta");
    
    companion object {
        fun fromString(value: String): GpsEventType? {
            return values().find { it.apiValue == value }
        }
    }
}

