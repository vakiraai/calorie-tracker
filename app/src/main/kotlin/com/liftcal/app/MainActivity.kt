package com.liftcal.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.liftcal.app.ui.LiftCalNavHost
import com.liftcal.app.ui.theme.LiftCalTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LiftCalTheme {
                LiftCalNavHost()
            }
        }
    }
}
