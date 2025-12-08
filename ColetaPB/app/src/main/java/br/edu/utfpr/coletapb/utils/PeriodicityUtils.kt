package br.edu.utfpr.coletapb.utils

import java.util.Calendar
import java.util.Locale

/**
 * Utilitários para trabalhar com periodicity (formato cron)
 * Formato: "minuto hora * * dia_da_semana"
 * Exemplo: "0 8 * * 1" = Segunda-feira às 8:00
 */
object PeriodicityUtils {
    
    /**
     * Dias da semana em português
     */
    private val weekDays = listOf(
        "Domingo", "Segunda-feira", "Terça-feira", "Quarta-feira",
        "Quinta-feira", "Sexta-feira", "Sábado"
    )
    
    /**
     * Verifica se hoje é um dia permitido para a rota baseado na periodicity
     * @param periodicity Formato cron: "minuto hora * * dia_da_semana"
     * @return true se hoje é um dia permitido, false caso contrário
     */
    fun isTodayAllowed(periodicity: String?): Boolean {
        if (periodicity.isNullOrBlank()) {
            // Se não tem periodicity, permite qualquer dia
            return true
        }
        
        try {
            val parts = periodicity.trim().split("\\s+".toRegex())
            if (parts.size < 5) {
                // Formato inválido, permite por padrão
                return true
            }
            
            val dayOfWeek = parts[4] // Último campo é o dia da semana
            
            // Se for "*", permite todos os dias
            if (dayOfWeek == "*") {
                return true
            }
            
            // Pega o dia da semana atual (Calendar: 1=domingo, 2=segunda, etc)
            val calendar = Calendar.getInstance()
            val currentDayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
            
            // Converte para formato cron (0=domingo, 1=segunda, etc)
            // Calendar.SUNDAY = 1 -> cron 0
            // Calendar.MONDAY = 2 -> cron 1
            // Calendar.TUESDAY = 3 -> cron 2
            // etc.
            val cronDayOfWeek = if (currentDayOfWeek == Calendar.SUNDAY) 0 else currentDayOfWeek - 1
            
            // Verifica se o dia atual está na lista de dias permitidos
            if (dayOfWeek.contains(",")) {
                // Múltiplos dias separados por vírgula
                val allowedDays = dayOfWeek.split(",").map { it.trim().toInt() }
                return allowedDays.contains(cronDayOfWeek)
            } else if (dayOfWeek.contains("-")) {
                // Range de dias (ex: "1-5" = segunda a sexta)
                val range = dayOfWeek.split("-")
                val start = range[0].trim().toInt()
                val end = range[1].trim().toInt()
                return cronDayOfWeek in start..end
            } else {
                // Dia único
                return cronDayOfWeek == dayOfWeek.toInt()
            }
        } catch (e: Exception) {
            // Em caso de erro no parse, permite por padrão
            return true
        }
    }
    
    /**
     * Formata a periodicity em uma string legível
     * @param periodicity Formato cron: "minuto hora * * dia_da_semana"
     * @return String formatada como "Segunda-feira às 8:00"
     */
    fun formatPeriodicity(periodicity: String?): String {
        if (periodicity.isNullOrBlank()) {
            return "Disponível todos os dias"
        }
        
        try {
            val parts = periodicity.trim().split("\\s+".toRegex())
            if (parts.size < 5) {
                return "Horário não especificado"
            }
            
            val minute = parts[0].toIntOrNull() ?: 0
            val hour = parts[1].toIntOrNull() ?: 0
            val dayOfWeek = parts[4]
            
            // Formata hora
            val timeStr = String.format(Locale.getDefault(), "%02d:%02d", hour, minute)
            
            // Formata dias
            val daysStr = when {
                dayOfWeek == "*" -> "Todos os dias"
                dayOfWeek.contains(",") -> {
                    // Múltiplos dias
                    val days = dayOfWeek.split(",").mapNotNull { dayNum ->
                        dayNum.trim().toIntOrNull()?.let { weekDays.getOrNull(it) }
                    }
                    if (days.isEmpty()) "Dias não especificados" else days.joinToString(", ")
                }
                dayOfWeek.contains("-") -> {
                    // Range de dias
                    val range = dayOfWeek.split("-")
                    val start = range[0].trim().toIntOrNull()
                    val end = range[1].trim().toIntOrNull()
                    if (start != null && end != null) {
                        val startDay = weekDays.getOrNull(start) ?: ""
                        val endDay = weekDays.getOrNull(end) ?: ""
                        "$startDay a $endDay"
                    } else {
                        "Dias não especificados"
                    }
                }
                else -> {
                    // Dia único
                    dayOfWeek.toIntOrNull()?.let { weekDays.getOrNull(it) } ?: "Dia não especificado"
                }
            }
            
            return "$daysStr às $timeStr"
        } catch (e: Exception) {
            return "Horário não especificado"
        }
    }
    
    /**
     * Retorna apenas os dias da semana permitidos
     * @param periodicity Formato cron
     * @return Lista de nomes dos dias da semana
     */
    fun getAllowedDays(periodicity: String?): List<String> {
        if (periodicity.isNullOrBlank()) {
            return weekDays
        }
        
        try {
            val parts = periodicity.trim().split("\\s+".toRegex())
            if (parts.size < 5) {
                return emptyList()
            }
            
            val dayOfWeek = parts[4]
            
            return when {
                dayOfWeek == "*" -> weekDays
                dayOfWeek.contains(",") -> {
                    dayOfWeek.split(",").mapNotNull { dayNum ->
                        dayNum.trim().toIntOrNull()?.let { weekDays.getOrNull(it) }
                    }
                }
                dayOfWeek.contains("-") -> {
                    val range = dayOfWeek.split("-")
                    val start = range[0].trim().toIntOrNull() ?: 0
                    val end = range[1].trim().toIntOrNull() ?: 6
                    weekDays.filterIndexed { index, _ -> index in start..end }
                }
                else -> {
                    dayOfWeek.toIntOrNull()?.let { weekDays.getOrNull(it)?.let { listOf(it) } } ?: emptyList()
                }
            }
        } catch (e: Exception) {
            return emptyList()
        }
    }
}

