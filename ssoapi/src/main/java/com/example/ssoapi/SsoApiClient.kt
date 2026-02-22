package com.example.ssoapi

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Result type for callback-based operations
 */
sealed class AuthCallbackResult {
    data class Success(val account: Account) : AuthCallbackResult()
    data class Failure(val message: String) : AuthCallbackResult()
}

/**
 * SsoApiClient is the main entry point for interacting with the SSO Service.
 * It handles binding to the external middleware service (com.example.service)
 * and provides methods for login, logout, register, and account management.
 *
 * This client uses ON-DEMAND connection - it connects when a call is made,
 * not maintaining a persistent connection.
 */
class SsoApiClient(private val context: Context) {

    companion object {
        private const val TAG = "SsoApiClient"
        private const val SSO_SERVICE_PACKAGE = "com.example.service"
        private const val SSO_SERVICE_CLASS = "com.example.service.SsoService"
        private const val SSO_SERVICE_ACTION = "com.example.service.SSO_SERVICE"
        private const val CONNECTION_TIMEOUT_MS = 5000L
        private const val CALLBACK_TIMEOUT_MS = 30000L
        private const val MAX_CONNECTION_RETRIES = 3
        private const val RETRY_DELAY_MS = 1000L
    }

    private var ssoService: sso? = null
    private var isBound = false
    private var pendingConnection: ((Boolean) -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Service connected: $name")
            ssoService = sso.Stub.asInterface(service)
            isBound = true
            pendingConnection?.invoke(true)
            pendingConnection = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Service disconnected: $name")
            ssoService = null
            isBound = false
        }
    }

    /**
     * Connect to the SSO Service on-demand.
     * This is a suspend function that waits for the connection to be established.
     * Retries up to MAX_CONNECTION_RETRIES times with RETRY_DELAY_MS between each attempt.
     * @return true if connected successfully, false otherwise
     */
    private suspend fun ensureConnected(): Boolean {
        if (isBound && ssoService != null) {
            Log.d(TAG, "Already connected to service")
            return true
        }

        repeat(MAX_CONNECTION_RETRIES) { attempt ->
            Log.d(TAG, "Connection attempt ${attempt + 1} of $MAX_CONNECTION_RETRIES")
            val connected = tryConnect()
            if (connected) {
                Log.d(TAG, "Connected on attempt ${attempt + 1}")
                return true
            }
            if (attempt < MAX_CONNECTION_RETRIES - 1) {
                Log.w(TAG, "Attempt ${attempt + 1} failed — retrying in ${RETRY_DELAY_MS}ms")
                delay(RETRY_DELAY_MS)
            }
        }

        Log.e(TAG, "All $MAX_CONNECTION_RETRIES connection attempts failed")
        return false
    }

    /**
     * Single connection attempt to the SSO Service.
     * Must be called from a coroutine context.
     * @return true if the service connected within CONNECTION_TIMEOUT_MS
     */
    private suspend fun tryConnect(): Boolean = withContext(Dispatchers.Main) {
        val intent = Intent(SSO_SERVICE_ACTION).apply {
            setPackage(SSO_SERVICE_PACKAGE)
            setClassName(SSO_SERVICE_PACKAGE, SSO_SERVICE_CLASS)
        }

        try {
            val connected = withTimeoutOrNull(CONNECTION_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    pendingConnection = { success ->
                        if (continuation.isActive) {
                            continuation.resume(success)
                        }
                    }

                    val bindResult = try {
                        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
                    } catch (e: SecurityException) {
                        Log.w(TAG, "Cannot bind to SSO service (SecurityException): ${e.message}")
                        false
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to bind to service: ${e.message}")
                        false
                    }

                    if (!bindResult) {
                        pendingConnection = null
                        if (continuation.isActive) {
                            continuation.resume(false)
                        }
                    }

                    continuation.invokeOnCancellation {
                        pendingConnection = null
                    }
                }
            }

            connected == true
        } catch (e: Exception) {
            Log.e(TAG, "Error during connection attempt", e)
            false
        }
    }

    /**
     * Unbind from the SSO Service. Call this when done using the client.
     */
    fun unbind() {
        if (isBound) {
            try {
                context.unbindService(serviceConnection)
            } catch (e: Exception) {
                Log.e(TAG, "Error unbinding service", e)
            }
            isBound = false
            ssoService = null
        }
    }

    /**
     * Check if the client is currently bound to the service.
     */
    fun isConnected(): Boolean = isBound && ssoService != null

    /**
     * Creates an IAuthCallback that resumes the given continuation
     */
    private fun createCallback(
        onSuccess: (Account) -> Unit,
        onFailure: (String) -> Unit
    ): IAuthCallback {
        return object : IAuthCallback.Stub() {
            private var resultReceived = false
            private var wasSuccess = false

            override fun onResult(result: AuthResult) {
                Log.d(TAG, "onResult: success=${result.success}, fail=${result.fail}, message=${result.message}")
                resultReceived = true
                wasSuccess = result.success

                if (result.fail) {
                    mainHandler.post {
                        onFailure(result.message ?: "Unknown error")
                    }
                }
                // If success, we wait for onAccountReceived
            }

            override fun onAccountReceived(account: Account) {
                Log.d(TAG, "onAccountReceived: guid=${account.guid}, mail=${account.mail}")
                if (wasSuccess) {
                    mainHandler.post {
                        onSuccess(account)
                    }
                }
            }
        }
    }

    /**
     * Login with email and password via AIDL service.
     */
    suspend fun login(mail: String, password: String): Result<Account> {
        if (!ensureConnected()) {
            return Result.failure(IllegalStateException("Could not connect to SSO Service. Please ensure the service is installed."))
        }

        return withContext(Dispatchers.IO) {
            val service = ssoService
            if (service == null) {
                return@withContext Result.failure(IllegalStateException("Service not connected."))
            }

            try {
                withTimeoutOrNull(CALLBACK_TIMEOUT_MS) {
                    suspendCancellableCoroutine { continuation ->
                        val callback = createCallback(
                            onSuccess = { account ->
                                if (continuation.isActive) continuation.resume(Result.success(account))
                            },
                            onFailure = { message ->
                                if (continuation.isActive) continuation.resume(Result.failure(Exception(message)))
                            }
                        )
                        try {
                            service.login(mail, password, callback)
                        } catch (e: RemoteException) {
                            if (continuation.isActive) continuation.resume(Result.failure(e))
                        }
                    }
                } ?: Result.failure(Exception("Login timeout"))
            } catch (e: Exception) {
                Log.e(TAG, "Login failed", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Register with email and password via AIDL service.
     */
    suspend fun register(mail: String, password: String): Result<Account> {
        if (!ensureConnected()) {
            return Result.failure(IllegalStateException("Could not connect to SSO Service."))
        }

        return withContext(Dispatchers.IO) {
            val service = ssoService
            if (service == null) {
                return@withContext Result.failure(IllegalStateException("Service not connected."))
            }

            try {
                withTimeoutOrNull(CALLBACK_TIMEOUT_MS) {
                    suspendCancellableCoroutine { continuation ->
                        val callback = createCallback(
                            onSuccess = { account ->
                                if (continuation.isActive) continuation.resume(Result.success(account))
                            },
                            onFailure = { message ->
                                if (continuation.isActive) continuation.resume(Result.failure(Exception(message)))
                            }
                        )
                        try {
                            service.register(mail, password, callback)
                        } catch (e: RemoteException) {
                            if (continuation.isActive) continuation.resume(Result.failure(e))
                        }
                    }
                } ?: Result.failure(Exception("Register timeout"))
            } catch (e: Exception) {
                Log.e(TAG, "Register failed", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Fetch token only (does NOT save anything).
     */
    suspend fun fetchToken(mail: String, password: String): Result<Account> {
        if (!ensureConnected()) {
            return Result.failure(IllegalStateException("Could not connect to SSO Service."))
        }

        return withContext(Dispatchers.IO) {
            val service = ssoService
            if (service == null) {
                return@withContext Result.failure(IllegalStateException("Service not connected."))
            }

            try {
                withTimeoutOrNull(CALLBACK_TIMEOUT_MS) {
                    suspendCancellableCoroutine { continuation ->
                        val callback = createCallback(
                            onSuccess = { account ->
                                if (continuation.isActive) continuation.resume(Result.success(account))
                            },
                            onFailure = { message ->
                                if (continuation.isActive) continuation.resume(Result.failure(Exception(message)))
                            }
                        )
                        try {
                            service.fetchToken(mail, password, callback)
                        } catch (e: RemoteException) {
                            if (continuation.isActive) continuation.resume(Result.failure(e))
                        }
                    }
                } ?: Result.failure(Exception("FetchToken timeout"))
            } catch (e: Exception) {
                Log.e(TAG, "FetchToken failed", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Fetch account info only (does NOT save anything).
     */
    suspend fun fetchAccountInfo(guid: String, sessionToken: String): Result<Account> {
        if (!ensureConnected()) {
            return Result.failure(IllegalStateException("Could not connect to SSO Service."))
        }

        return withContext(Dispatchers.IO) {
            val service = ssoService
            if (service == null) {
                return@withContext Result.failure(IllegalStateException("Service not connected."))
            }

            try {
                withTimeoutOrNull(CALLBACK_TIMEOUT_MS) {
                    suspendCancellableCoroutine { continuation ->
                        val callback = createCallback(
                            onSuccess = { account ->
                                if (continuation.isActive) continuation.resume(Result.success(account))
                            },
                            onFailure = { message ->
                                if (continuation.isActive) continuation.resume(Result.failure(Exception(message)))
                            }
                        )
                        try {
                            service.fetchAccountInfo(guid, sessionToken, callback)
                        } catch (e: RemoteException) {
                            if (continuation.isActive) continuation.resume(Result.failure(e))
                        }
                    }
                } ?: Result.failure(Exception("FetchAccountInfo timeout"))
            } catch (e: Exception) {
                Log.e(TAG, "FetchAccountInfo failed", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Logout a specific account by GUID.
     */
    suspend fun logout(guid: String): Result<Unit> {
        Log.d(TAG, "Logout called with guid='$guid'")

        if (!ensureConnected()) {
            return Result.failure(IllegalStateException("Could not connect to SSO Service."))
        }

        return withContext(Dispatchers.IO) {
            val service = ssoService
            if (service == null) {
                return@withContext Result.failure(IllegalStateException("Service not connected."))
            }

            try {
                Log.d(TAG, "Calling service.logout('$guid')")
                service.logout(guid)
                Log.d(TAG, "Logout call completed for: $guid")
                Result.success(Unit)
            } catch (e: RemoteException) {
                Log.e(TAG, "Logout failed with RemoteException", e)
                Result.failure(e)
            } catch (e: Exception) {
                Log.e(TAG, "Logout failed", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Logout all accounts.
     */
    suspend fun logoutAll(): Result<Unit> {
        if (!ensureConnected()) {
            return Result.failure(IllegalStateException("Could not connect to SSO Service."))
        }

        return withContext(Dispatchers.IO) {
            val service = ssoService
            if (service == null) {
                return@withContext Result.failure(IllegalStateException("Service not connected."))
            }

            try {
                service.logoutAll()
                Log.d(TAG, "Logout all accounts completed")
                Result.success(Unit)
            } catch (e: RemoteException) {
                Log.e(TAG, "Logout all failed with RemoteException", e)
                Result.failure(e)
            } catch (e: Exception) {
                Log.e(TAG, "Logout all failed", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Switch to a different account by GUID.
     */
    suspend fun switchAccount(guid: String): Result<Unit> {
        if (!ensureConnected()) {
            return Result.failure(IllegalStateException("Could not connect to SSO Service."))
        }

        return withContext(Dispatchers.IO) {
            val service = ssoService
            if (service == null) {
                return@withContext Result.failure(IllegalStateException("Service not connected."))
            }

            try {
                service.switchAccount(guid)
                Log.d(TAG, "Switch account call completed for guid: $guid")
                Result.success(Unit)
            } catch (e: RemoteException) {
                Log.e(TAG, "Switch account failed with RemoteException", e)
                Result.failure(e)
            } catch (e: Exception) {
                Log.e(TAG, "Switch account failed", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Get the currently active account.
     */
    suspend fun getActiveAccount(): Account? {
        Log.d(TAG, "getActiveAccount() called - attempting to connect")
        if (!ensureConnected()) {
            Log.w(TAG, "Could not connect to get active account")
            return null
        }

        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "getActiveAccount() - calling service.activeAccount")
                val account = ssoService?.activeAccount
                if (account != null) {
                    Log.d(TAG, "Active account: guid='${account.guid}', mail='${account.mail}'")
                } else {
                    Log.d(TAG, "No active account")
                }
                account
            } catch (e: RemoteException) {
                Log.e(TAG, "Failed to get active account", e)
                null
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get active account", e)
                null
            }
        }
    }

    /**
     * Get all logged-in accounts.
     */
    suspend fun getAllAccounts(): List<Account> {
        Log.d(TAG, "getAllAccounts() called - attempting to connect")
        if (!ensureConnected()) {
            Log.w(TAG, "Could not connect to get all accounts")
            return emptyList()
        }

        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "getAllAccounts() - calling service.allAccounts")
                val rawAccounts = ssoService?.allAccounts
                Log.d(TAG, "getAllAccounts() - rawAccounts received: type=${rawAccounts?.javaClass?.name}, size=${rawAccounts?.size}")
                if (rawAccounts != null) {
                    rawAccounts.forEachIndexed { index, account ->
                        Log.d(TAG, "  Raw Account[$index]: ${if (account == null) "NULL" else "guid=${account.guid}, mail=${account.mail}"}")
                    }
                }
                val accounts = rawAccounts?.filterNotNull() ?: emptyList()
                Log.d(TAG, "Got ${accounts.size} accounts from service (after filterNotNull)")
                accounts.forEachIndexed { index, account ->
                    Log.d(TAG, "  Account[$index]: guid='${account.guid}', mail='${account.mail}', isActive=${account.isActive}")
                }
                accounts
            } catch (e: RemoteException) {
                Log.e(TAG, "Failed to get all accounts", e)
                emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get all accounts", e)
                emptyList()
            }
        }
    }
}
