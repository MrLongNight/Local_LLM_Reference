package de.mrlongnight.llmreference

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "Local LLM Reference\n\nEngine bootstrap in progress.\nNo inference backend is enabled yet."
            textSize = 18f
            setPadding(32, 48, 32, 32)
        })
    }
}
