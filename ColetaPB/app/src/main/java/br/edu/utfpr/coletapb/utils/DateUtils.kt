package br.edu.utfpr.coletapb.utils

import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

/**
 * Utilitário para conversão de datas e timezones
 * Converte datas UTC do backend para timezone local do dispositivo
 */
object DateUtils {
    
    /**
     * Converte uma string ISO 8601 (UTC) para Date no timezone local
     * O backend envia datas em UTC (timezone Z), e este método converte para o timezone local do dispositivo
     */
    fun parseUtcToLocal(isoString: String?): Date? {
        if (isoString == null || isoString.isEmpty()) return null
        
        return try {
            // Remove espaços em branco
            val cleanString = isoString.trim()
            
            // Se a string contém ponto decimal, pode ter milissegundos ou microssegundos
            // Trunca para milissegundos (3 dígitos) se necessário
            val normalizedString = if (cleanString.contains(".")) {
                val dotIndex = cleanString.indexOf(".")
                if (dotIndex > 0) {
                    // Verifica se tem Z, + ou - após o ponto (offset de timezone)
                    val hasTimezone = cleanString.contains("Z") || 
                                     (cleanString.contains("+") && cleanString.indexOf("+") > dotIndex) ||
                                     (cleanString.contains("-") && cleanString.lastIndexOf("-") > dotIndex && cleanString.lastIndexOf("-") > 10)
                    
                    if (hasTimezone) {
                        // Tem timezone, precisa tratar separadamente
                        if (cleanString.endsWith("Z")) {
                            val zIndex = cleanString.indexOf("Z")
                            val afterDot = cleanString.substring(dotIndex + 1, zIndex)
                            if (afterDot.length > 3) {
                                // Tem microssegundos, trunca para milissegundos
                                cleanString.substring(0, dotIndex + 1) + afterDot.substring(0, 3) + "Z"
                            } else {
                                cleanString
                            }
                        } else {
                            // Tem offset (+ ou -), não normaliza a parte fracionária aqui
                            cleanString
                        }
                    } else {
                        // Não tem timezone, pode ter microssegundos
                        val afterDot = cleanString.substring(dotIndex + 1)
                        if (afterDot.length > 3) {
                            // Tem microssegundos, trunca para milissegundos
                            cleanString.substring(0, dotIndex + 1) + afterDot.substring(0, 3)
                        } else {
                            cleanString
                        }
                    }
                } else {
                    cleanString
                }
            } else {
                cleanString
            }
            
            // Tenta diferentes formatos ISO 8601
            when {
                // Formato com Z e milissegundos: 2025-12-06T14:30:00.123Z
                normalizedString.contains(".") && normalizedString.endsWith("Z") -> {
                    try {
                        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                        format.timeZone = TimeZone.getTimeZone("UTC")
                        format.parse(normalizedString)
                    } catch (e: Exception) {
                        Log.e("DateUtils", "Erro ao fazer parse com milissegundos e Z: ${e.message}")
                        null
                    }
                }
                // Formato com Z sem milissegundos: 2025-12-06T14:30:00Z
                normalizedString.endsWith("Z") -> {
                    try {
                        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                        format.timeZone = TimeZone.getTimeZone("UTC")
                        format.parse(normalizedString)
                    } catch (e: Exception) {
                        Log.e("DateUtils", "Erro ao fazer parse com Z sem milissegundos: ${e.message}")
                        null
                    }
                }
                // Formato sem Z mas com milissegundos: 2025-12-06T20:47:37.001
                normalizedString.contains(".") && !normalizedString.contains("Z") && !normalizedString.contains("+") && !normalizedString.contains("-", ignoreCase = true) -> {
                    try {
                        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
                        format.timeZone = TimeZone.getTimeZone("UTC")
                        format.parse(normalizedString)
                    } catch (e: Exception) {
                        Log.e("DateUtils", "Erro ao fazer parse com milissegundos sem Z: ${e.message}")
                        null
                    }
                }
                // Formato com offset: 2025-12-06T14:30:00+00:00 ou 2025-12-06T14:30:00-03:00
                normalizedString.contains("+") || (normalizedString.contains("-") && normalizedString.length > 19 && normalizedString.lastIndexOf("-") > 10) -> {
                    try {
                        // Tenta com offset completo
                        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
                        format.parse(normalizedString)
                    } catch (e: Exception) {
                        try {
                            // Se falhar, tenta formato mais simples
                            val datePart = normalizedString.substringBefore("+").substringBefore("-")
                            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                            format.timeZone = TimeZone.getTimeZone("UTC")
                            format.parse(datePart)
                        } catch (e2: Exception) {
                            Log.e("DateUtils", "Erro ao fazer parse com offset: ${e2.message}")
                            null
                        }
                    }
                }
                // Formato sem timezone (assume UTC): 2025-12-06T14:30:00
                else -> {
                    try {
                        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                        format.timeZone = TimeZone.getTimeZone("UTC")
                        format.parse(normalizedString)
                    } catch (e: Exception) {
                        Log.e("DateUtils", "Erro ao fazer parse sem timezone: ${e.message}")
                        null
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("DateUtils", "Erro ao fazer parse de data: $isoString - ${e.message}", e)
            null
        }
    }
    
    /**
     * Formata uma data para exibição no timezone local
     */
    fun formatForDisplay(date: Date?, datePattern: String = "dd/MM/yyyy", timePattern: String = "HH:mm"): String {
        if (date == null) return ""
        
        val dateFormat = SimpleDateFormat(datePattern, Locale.getDefault())
        val timeFormat = SimpleDateFormat(timePattern, Locale.getDefault())
        
        // Usa timezone local do dispositivo
        dateFormat.timeZone = TimeZone.getDefault()
        timeFormat.timeZone = TimeZone.getDefault()
        
        return "${dateFormat.format(date)} · ${timeFormat.format(date)}"
    }
    
    /**
     * Formata apenas a data (sem hora)
     */
    fun formatDateOnly(date: Date?, pattern: String = "dd/MM/yyyy"): String {
        if (date == null) return ""
        
        val dateFormat = SimpleDateFormat(pattern, Locale.getDefault())
        dateFormat.timeZone = TimeZone.getDefault()
        
        return dateFormat.format(date)
    }
    
    /**
     * Formata apenas a hora
     */
    fun formatTimeOnly(date: Date?, pattern: String = "HH:mm"): String {
        if (date == null) return ""
        
        val timeFormat = SimpleDateFormat(pattern, Locale.getDefault())
        timeFormat.timeZone = TimeZone.getDefault()
        
        return timeFormat.format(date)
    }
    
    /**
     * Converte uma data (Date representa momento no tempo, sem timezone) 
     * para string ISO 8601 em UTC (formato: yyyy-MM-ddTHH:mm:ss.SSSZ)
     * 
     * Nota: Date em Java/Kotlin é timezone-agnostic (apenas millis desde epoch UTC).
     * Este método formata o momento representado pelo Date como se fosse UTC,
     * que é o formato esperado pelo backend.
     */
    fun formatLocalToUtc(date: Date?): String? {
        if (date == null) return null
        
        // Formata o Date (momento no tempo) como string UTC
        // O Date já representa o momento correto, apenas formatamos como UTC
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        
        return format.format(date)
    }
    
    /**
     * Calcula a duração entre duas datas
     * Retorna no formato HH:mm (horas:minutos)
     */
    fun calculateDuration(start: Date?, end: Date?): String {
        if (start == null || end == null) return "-"
        
        val diff = end.time - start.time
        val totalMinutes = diff / (1000 * 60)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        
        return String.format("%02d:%02d", hours, minutes)
    }
}

