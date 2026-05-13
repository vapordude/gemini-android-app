package com.gemini.app.ui.login

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.auth.GoogleAuthUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GoogleAuthService(private val context: Context) {

    // Scopes needed for Gemini API via OAuth
    private val SCOPE_CLOUD_PLATFORM = "https://www.googleapis.com/auth/cloud-platform"
    private val SCOPE_GENERATIVE_LANGUAGE = "https://www.googleapis.com/auth/generative-language"

    private fun getSignInOptions(): GoogleSignInOptions {
        return GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(SCOPE_CLOUD_PLATFORM), Scope(SCOPE_GENERATIVE_LANGUAGE))
            .build()
    }

    fun getSignInClient(): GoogleSignInClient {
        return GoogleSignIn.getClient(context, getSignInOptions())
    }

    fun getSignInIntent(): Intent {
        return getSignInClient().signInIntent
    }

    fun parseSignInResult(data: Intent?): GoogleSignInAccount? {
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        return try {
            task.getResult(ApiException::class.java)
        } catch (e: ApiException) {
            e.printStackTrace()
            null
        }
    }

    suspend fun getAccessToken(account: GoogleSignInAccount): String? = withContext(Dispatchers.IO) {
        try {
            val scopes = "oauth2:$SCOPE_CLOUD_PLATFORM $SCOPE_GENERATIVE_LANGUAGE"
            GoogleAuthUtil.getToken(context, account.account!!, scopes)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun getLastSignedInAccount(): GoogleSignInAccount? {
        return GoogleSignIn.getLastSignedInAccount(context)
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        try {
            getSignInClient().signOut()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
