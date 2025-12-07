package br.edu.utfpr.coletapb

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import br.edu.utfpr.coletapb.data.local.SharedPreferencesHelper
import br.edu.utfpr.coletapb.data.repository.TokenRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ColetaPBApplication : Application(), DefaultLifecycleObserver, android.app.Application.ActivityLifecycleCallbacks {
    
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var prefsHelper: SharedPreferencesHelper
    private lateinit var tokenRepository: TokenRepository
    private var isAppInForeground = false
    private var tokenRefreshJob: kotlinx.coroutines.Job? = null
    private var activeActivities = 0
    private var wasAppClosed = false
    private var tokenCleanupJob: kotlinx.coroutines.Job? = null
    
    override fun onCreate() {
        super<Application>.onCreate()
        
        prefsHelper = SharedPreferencesHelper(this)
        tokenRepository = TokenRepository(prefsHelper, this)
        
        // Observa o ciclo de vida do processo (app inteiro)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        
        // Observa o ciclo de vida de todas as Activities
        registerActivityLifecycleCallbacks(this)
        
        Log.d("ColetaPBApplication", "Application criada")
    }
    
    /**
     * Chamado quando o app vai para foreground (primeira Activity visível)
     */
    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        isAppInForeground = true
        Log.d("ColetaPBApplication", "App em foreground")
        
        // Para a renovação automática quando em foreground
        // (renovação será feita pelo AuthInterceptor quando necessário)
        stopTokenRefresh()
    }
    
    /**
     * Chamado quando o app vai para background (todas as Activities não visíveis)
     */
    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        isAppInForeground = false
        Log.d("ColetaPBApplication", "App em background")
        
        // Inicia renovação automática de token quando em background
        startTokenRefresh()
    }
    
    /**
     * Inicia renovação automática de token quando o app está em background
     * Renova a cada 30 minutos (antes do token expirar)
     */
    private fun startTokenRefresh() {
        // Para qualquer job anterior
        stopTokenRefresh()
        
        tokenRefreshJob = applicationScope.launch {
            while (!isAppInForeground) {
                try {
                    val token = prefsHelper.getToken()
                    if (token != null && prefsHelper.isTokenValid()) {
                        // Verifica se precisa renovar (renova 5 minutos antes de expirar)
                        val expirationTime = br.edu.utfpr.coletapb.utils.TokenUtils.getTokenExpirationTime(token)
                        if (expirationTime != null) {
                            val timeUntilExpiration = expirationTime - System.currentTimeMillis()
                            val fiveMinutes = 5 * 60 * 1000L // 5 minutos em ms
                            
                            if (timeUntilExpiration <= fiveMinutes) {
                                Log.d("ColetaPBApplication", "Token próximo de expirar, renovando...")
                                val success = tokenRepository.refreshToken()
                                if (success) {
                                    Log.d("ColetaPBApplication", "Token renovado com sucesso em background")
                                } else {
                                    Log.w("ColetaPBApplication", "Falha ao renovar token em background")
                                }
                            } else {
                                Log.d("ColetaPBApplication", "Token ainda válido por mais ${timeUntilExpiration / 60000} minutos")
                            }
                        }
                    }
                    
                    // Aguarda 30 minutos antes de verificar novamente
                    delay(30 * 60 * 1000L)
                } catch (e: CancellationException) {
                    // Cancelamento esperado quando o app volta para foreground
                    Log.d("ColetaPBApplication", "Renovação de token cancelada (app voltou para foreground)")
                    // Não precisa fazer nada, apenas sair do loop
                    break
                } catch (e: Exception) {
                    Log.e("ColetaPBApplication", "Erro ao renovar token em background: ${e.message}", e)
                    // Em caso de erro, aguarda 5 minutos antes de tentar novamente
                    try {
                        delay(5 * 60 * 1000L)
                    } catch (cancelException: CancellationException) {
                        // Se foi cancelado durante o delay de retry, apenas sai
                        break
                    }
                }
            }
        }
    }
    
    /**
     * Para a renovação automática de token
     */
    private fun stopTokenRefresh() {
        tokenRefreshJob?.cancel()
        tokenRefreshJob = null
    }
    
    /**
     * Limpa o token quando o app é realmente fechado
     * Isso é chamado quando o processo é finalizado
     */
    fun clearTokenOnAppClose() {
        Log.d("ColetaPBApplication", "App sendo fechado, limpando token")
        prefsHelper.clearAll()
        stopTokenRefresh()
        wasAppClosed = true
    }
    
    // ActivityLifecycleCallbacks para detectar quando todas as Activities são destruídas
    override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {
        activeActivities++
        wasAppClosed = false
        
        // Cancela qualquer job de limpeza de token se uma nova Activity foi criada
        tokenCleanupJob?.cancel()
        tokenCleanupJob = null
        
        Log.d("ColetaPBApplication", "Activity criada: ${activity.javaClass.simpleName}, total: $activeActivities")
    }
    
    override fun onActivityStarted(activity: android.app.Activity) {
        // Não faz nada
    }
    
    override fun onActivityResumed(activity: android.app.Activity) {
        // Não faz nada
    }
    
    override fun onActivityPaused(activity: android.app.Activity) {
        // Não faz nada
    }
    
    override fun onActivityStopped(activity: android.app.Activity) {
        // Não faz nada
    }
    
    override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {
        // Não faz nada
    }
    
    override fun onActivityDestroyed(activity: android.app.Activity) {
        activeActivities--
        Log.d("ColetaPBApplication", "Activity destruída: ${activity.javaClass.simpleName}, restantes: $activeActivities")
        
        // Se todas as Activities foram destruídas e o app não está em foreground,
        // aguarda um pouco para ver se uma nova Activity será criada (app apenas minimizado)
        // ou se o app foi realmente fechado
        if (activeActivities == 0 && !isAppInForeground) {
            Log.d("ColetaPBApplication", "Todas as Activities foram destruídas e app não está em foreground")
            
            // Cancela qualquer job anterior
            tokenCleanupJob?.cancel()
            
            // Aguarda 2 segundos antes de verificar se deve limpar o token
            // Se uma nova Activity for criada nesse tempo, o job será cancelado
            tokenCleanupJob = applicationScope.launch {
                try {
                    delay(2000) // 2 segundos de delay
                } catch (e: CancellationException) {
                    // Cancelamento esperado quando uma nova Activity é criada
                    Log.d("ColetaPBApplication", "Job de limpeza cancelado (nova Activity criada)")
                    return@launch
                }
                
                // Verifica novamente se ainda não há Activities ativas
                if (activeActivities == 0 && !isAppInForeground) {
                    Log.d("ColetaPBApplication", "App realmente fechado (nenhuma Activity criada em 2s)")
                    
                    // Verifica se há rota em andamento antes de limpar
                    try {
                        val executionRepo = br.edu.utfpr.coletapb.data.repository.ExecutionRepository(prefsHelper)
                        val currentExec = executionRepo.getMyCurrentExecution().getOrNull()
                        val hasActiveRoute = currentExec != null && currentExec.status == "IN_PROGRESS"
                        
                        if (!hasActiveRoute) {
                            // Não há rota ativa, pode limpar o token
                            Log.d("ColetaPBApplication", "Nenhuma rota ativa, limpando token")
                            withContext(Dispatchers.Main) {
                                prefsHelper.clearAll()
                                stopTokenRefresh()
                            }
                        } else {
                            Log.d("ColetaPBApplication", "Rota ativa encontrada, mantendo token para GPS tracking")
                        }
                    } catch (e: Exception) {
                        Log.e("ColetaPBApplication", "Erro ao verificar rota ativa: ${e.message}", e)
                        // Em caso de erro, limpa o token por segurança
                        withContext(Dispatchers.Main) {
                            prefsHelper.clearAll()
                            stopTokenRefresh()
                        }
                    }
                } else {
                    Log.d("ColetaPBApplication", "Nova Activity foi criada, não limpando token")
                }
            }
        }
    }
}

