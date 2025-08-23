package com.ducpq.androidtest.screens.login

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ducpq.androidtest.utils.OAuthConfig
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenResponse
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import net.openid.appauth.ClientAuthentication
import net.openid.appauth.ClientSecretBasic
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.StandardCharsets
import android.util.Base64
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.AuthorizationRequest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(navController: NavHostController) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }

    val authService = remember { AuthorizationService(context) }
    DisposableEffect(authService) {
        onDispose {
            authService.dispose()
        }
    }

    val authResultLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { result ->
            val intent = result.data
            if (intent == null) {
                Log.e("Auth", "Auth failed: The intent is null.")
                isLoading = false
                return@rememberLauncherForActivityResult
            }

            val response = AuthorizationResponse.fromIntent(intent)
            val ex = AuthorizationException.fromIntent(intent)

            if (response != null) {
                // The onResult callback is already on the main thread. We can now safely call the token exchange.
                performTokenExchangeAndSave(
                    context,
                    authService,
                    response,
                    navController,
                    onLoadingChange = { isLoading = it }
                )
            } else {
                isLoading = false
            }
        }
    )

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Login") })
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Lavega \n Android Test")
                Button(
                    onClick = {
                        isLoading = true
                        val serviceConfig = AuthorizationServiceConfiguration(
                            Uri.parse(OAuthConfig.AUTH_URI),
                            Uri.parse(OAuthConfig.TOKEN_URI)
                        )
                        val redirectUri = Uri.parse(OAuthConfig.REDIRECT_URI)
                        val codeVerifier = generateCodeVerifier()

                        val request = AuthorizationRequest.Builder(
                            serviceConfig,
                            OAuthConfig.CLIENT_ID,
                            ResponseTypeValues.CODE,
                            redirectUri
                        )
                            .setScope(OAuthConfig.SCOPE)
                            .setCodeVerifier(codeVerifier)
                            .build()


                        val authIntent = authService.getAuthorizationRequestIntent(request)
                        authResultLauncher.launch(authIntent)
                    },
                    modifier = Modifier.padding(16.dp),
                    enabled = !isLoading
                ) {
                    Text("Login with Google")
                }

                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
                }
            }
        }
    }
}

fun performTokenExchangeAndSave(
    context: Context,
    authService: AuthorizationService,
    authResponse: AuthorizationResponse,
    navController: NavHostController,
    onLoadingChange: (Boolean) -> Unit
) {
    // Android clients do not use a client secret. The AppAuth library handles the PKCE flow automatically.
    val tokenRequest = authResponse.createTokenExchangeRequest()

    authService.performTokenRequest(tokenRequest) { tokenResponse, ex ->
        if (tokenResponse != null) {
            Log.d("Auth", "Token exchange successful!")
            saveTokens(context, tokenResponse)
            navController.navigate("home")
        } else {
            Log.e("Auth", "Token exchange failed: ${ex?.message}")
        }
        onLoadingChange(false)
    }
}

fun saveTokens(context: Context, tokenResponse: TokenResponse) {
    try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        val sharedPreferences = EncryptedSharedPreferences.create(
            context,
            "secret_shared_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        with(sharedPreferences.edit()) {
            putString("accessToken", tokenResponse.accessToken)
            putString("refreshToken", tokenResponse.refreshToken)
            putString("idToken", tokenResponse.idToken)
            apply()
        }
        Log.d("Auth", "Tokens saved to EncryptedSharedPreferences.")
    } catch (e: Exception) {
        Log.e("Auth", "Failed to save tokens: ${e.message}")
    }
}

fun getUserIdToken(context: Context): String? {
    return try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val sharedPreferences = EncryptedSharedPreferences.create(
            context,
            "secret_shared_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        sharedPreferences.getString("idToken", null)
    } catch (e: Exception) {
        Log.e("Auth", "Failed to get token: ${e.message}")
        null
    }
}

fun clearTokens(context: Context) {
    try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val sharedPreferences = EncryptedSharedPreferences.create(
            context,
            "secret_shared_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        sharedPreferences.edit().clear().apply()
        Log.d("Auth", "Tokens cleared from EncryptedSharedPreferences.")
    } catch (e: Exception) {
        Log.e("Auth", "Failed to clear tokens: ${e.message}")
    }
}

fun generateCodeVerifier(): String {
    val allowedChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    return (1..64)
        .map { allowedChars.random() }
        .joinToString("")
}