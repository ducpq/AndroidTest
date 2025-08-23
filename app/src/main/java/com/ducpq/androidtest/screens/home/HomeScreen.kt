package com.ducpq.androidtest.screens.home

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ducpq.androidtest.screens.login.clearTokens
import com.ducpq.androidtest.screens.login.getUserIdToken
import com.ducpq.androidtest.screens.login.saveTokens
import com.ducpq.androidtest.utils.OAuthConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.TokenRequest
import net.openid.appauth.TokenResponse
import org.json.JSONObject
import java.nio.charset.StandardCharsets

@Composable
fun HomeScreen(navController: NavHostController) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val authService = remember { AuthorizationService(context) }

    var userName by remember { mutableStateOf("Loading...") }
    var userEmail by remember { mutableStateOf("Loading...") }
    var isLoading by remember { mutableStateOf(true) }

    suspend fun fetchUserData() {
        val idToken = getUserIdToken(context)
        idToken?.let { token ->
            val payload = try {
                val parts = token.split(".")
                val encodedPayload = parts[1]
                val decodedBytes = Base64.decode(encodedPayload, Base64.URL_SAFE)
                String(decodedBytes, StandardCharsets.UTF_8)
            } catch (e: Exception) {
                null
            }

            payload?.let { json ->
                try {
                    val jsonObject = JSONObject(json)
                    userName = jsonObject.optString("name", "Name Not Found")
                    userEmail = jsonObject.optString("email", "Email Not Found")
                    isLoading = false
                } catch (e: Exception) {
                    userName = "Error"
                    userEmail = "Error"
                    isLoading = false
                }
            } ?: run {
                // If decoding fails, attempt to refresh the token
                val refreshToken = getRefreshToken(context)
                if (refreshToken != null) {
                    Log.d("Auth", "Attempting to refresh token...")
                    val refreshedToken = withContext(Dispatchers.IO) {
                        try {
                            performTokenRefreshAndSave(context, authService, refreshToken)
                        } catch (e: Exception) {
                            Log.e("Auth", "Token refresh failed: ${e.message}")
                            null
                        }
                    }
                    if (refreshedToken != null) {
                        fetchUserData() // Retry fetching data with the new token
                    } else {
                        // Refresh failed, force re-login
                        withContext(Dispatchers.Main) {
                            clearTokens(context)
                            navController.navigate("login") {
                                popUpTo("home") { inclusive = true }
                            }
                        }
                    }
                } else {
                    // No refresh token, force re-login
                    withContext(Dispatchers.Main) {
                        clearTokens(context)
                        navController.navigate("login") {
                            popUpTo("home") { inclusive = true }
                        }
                    }
                }
            }
        } ?: run {
            // No ID token to begin with, force re-login
            withContext(Dispatchers.Main) {
                clearTokens(context)
                navController.navigate("login") {
                    popUpTo("home") { inclusive = true }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        coroutineScope.launch {
            fetchUserData()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            CircularProgressIndicator()
        } else {
            Column(
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Welcome to the Home Screen!")
                Text("Name: $userName", modifier = Modifier.padding(top = 16.dp))
                Text("Email: $userEmail", modifier = Modifier.padding(top = 8.dp))
                Button(
                    onClick = {
                        clearTokens(context)
                        navController.navigate("login") {
                            popUpTo("login") { inclusive = true }
                        }
                    },
                    modifier = Modifier.padding(top = 24.dp)
                ) {
                    Text("Logout")
                }
            }
        }
    }
}


fun getRefreshToken(context: Context): String? {
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
        sharedPreferences.getString("refreshToken", null)
    } catch (e: Exception) {
        null
    }
}
@Composable
fun SplashScreen(navController: NavHostController) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        coroutineScope.launch(Dispatchers.IO) {
            val idToken = getUserIdToken(context)
            withContext(Dispatchers.Main) {
                if (idToken != null) {
                    navController.navigate("home") {
                        popUpTo("splash") { inclusive = true }
                    }
                } else {
                    navController.navigate("login") {
                        popUpTo("splash") { inclusive = true }
                    }
                }
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

suspend fun performTokenRefreshAndSave(context: Context, authService: AuthorizationService, refreshToken: String): TokenResponse? {
    val serviceConfig = AuthorizationServiceConfiguration(
        Uri.parse(OAuthConfig.AUTH_URI),
        Uri.parse(OAuthConfig.TOKEN_URI)
    )
    val tokenRequest = TokenRequest.Builder(
        serviceConfig,
        OAuthConfig.CLIENT_ID
    )
        .setRefreshToken(refreshToken)
        .build()

    return try {
        withContext(Dispatchers.IO) {
            var response: TokenResponse? = null
            authService.performTokenRequest(tokenRequest){tokenResponse, ex ->
                if (tokenResponse != null) {
                    saveTokens(context, tokenResponse)
                    response = tokenResponse
                }
            }
            response
        }
    } catch (e: Exception) {
        null
    }
}
