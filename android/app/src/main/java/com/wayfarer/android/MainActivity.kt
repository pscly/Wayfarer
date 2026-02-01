package com.wayfarer.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.wayfarer.android.ui.WayfarerApp
import com.wayfarer.android.ui.theme.WayfarerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            WayfarerTheme {
                WayfarerApp()
            }
        }
    }
}
