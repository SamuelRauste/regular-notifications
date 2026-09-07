package com.samuel.regularnotifications

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.samuel.regularnotifications.ui.RegularNotificationsApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appContainer = (application as RegularNotificationsApplication).appContainer
        setContent {
            RegularNotificationsTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    RegularNotificationsApp(appContainer)
                }
            }
        }
    }
}

@Composable
private fun RegularNotificationsTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}
