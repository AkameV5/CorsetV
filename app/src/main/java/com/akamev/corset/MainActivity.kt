package com.akamev.corset

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.akamev.corset.presentation.CorsetApp
import com.akamev.corset.presentation.theme.CorsetTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CorsetTheme {
                CorsetApp()
            }
        }
    }
}
