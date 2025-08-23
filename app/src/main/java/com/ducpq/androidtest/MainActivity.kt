package com.ducpq.androidtest

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ducpq.androidtest.screens.home.HomeScreen
import com.ducpq.androidtest.screens.home.SplashScreen
import com.ducpq.androidtest.screens.login.LoginScreen
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val navController = rememberNavController()
            NavHost(navController = navController, startDestination = "splash") {
                composable("login") {
                    // We don't need to pass authService here anymore
                    LoginScreen(navController)
                }
                composable("splash") {
                    SplashScreen(navController)
                }
                composable("home") { HomeScreen(navController) }
            }
        }
    }

}
