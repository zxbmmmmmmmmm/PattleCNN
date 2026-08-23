package org.bettafish.huelab

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        initializeAndroidPlatform(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            App()
        }
    }

    @Deprecated("Legacy callback used by the shared shader picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!handleAndroidShaderResult(requestCode, resultCode, data)) {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
