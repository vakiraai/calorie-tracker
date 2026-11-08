package com.liftcal.data.fitbit

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OAuth 2.0 authorization-code flow with PKCE (AppAuth generates the verifier) against Fitbit.
 * The redirect URI scheme must match `appAuthRedirectScheme` in the app manifest placeholders.
 */
class FitbitAuthManager(
    context: Context,
    private val clientId: String,
    private val redirectUri: Uri,
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("fitbit_auth", Context.MODE_PRIVATE)
    private val service by lazy { AuthorizationService(appContext) }

    private val config = AuthorizationServiceConfiguration(
        Uri.parse("https://www.fitbit.com/oauth2/authorize"),
        Uri.parse("https://api.fitbit.com/oauth2/token"),
    )

    private var state: AuthState = prefs.getString(KEY_STATE, null)
        ?.let { runCatching { AuthState.jsonDeserialize(it) }.getOrNull() }
        ?: AuthState(config)

    val isConfigured: Boolean get() = clientId.isNotBlank()
    val isAuthorized: Boolean get() = state.isAuthorized

    fun authorizationIntent(): Intent {
        check(isConfigured) { "Set fitbit.clientId in local.properties to enable Fitbit Web API access" }
        val request = AuthorizationRequest.Builder(config, clientId, ResponseTypeValues.CODE, redirectUri)
            .setScopes(SCOPES)
            .build()
        return service.getAuthorizationRequestIntent(request)
    }

    suspend fun handleAuthorizationResult(data: Intent) {
        val resp = AuthorizationResponse.fromIntent(data)
        val ex = AuthorizationException.fromIntent(data)
        state.update(resp, ex)
        if (resp == null) throw ex ?: IllegalStateException("Fitbit authorization cancelled")
        val tokenResponse = suspendCancellableCoroutine { cont ->
            service.performTokenRequest(resp.createTokenExchangeRequest()) { tr, tex ->
                if (tr != null) cont.resume(tr) else cont.resumeWithException(tex ?: IllegalStateException("token exchange failed"))
            }
        }
        state.update(tokenResponse, null)
        persist()
    }

    /** Returns a valid access token, refreshing it if needed. */
    suspend fun freshAccessToken(): String = suspendCancellableCoroutine { cont ->
        state.performActionWithFreshTokens(service) { token, _, ex ->
            persist()
            if (token != null) cont.resume(token) else cont.resumeWithException(ex ?: IllegalStateException("not authorized"))
        }
    }

    fun signOut() {
        state = AuthState(config)
        prefs.edit().remove(KEY_STATE).apply()
    }

    private fun persist() {
        prefs.edit().putString(KEY_STATE, state.jsonSerializeString()).apply()
    }

    companion object {
        private const val KEY_STATE = "auth_state"
        val SCOPES = listOf("heartrate", "activity", "profile", "cardio_fitness")
    }
}
