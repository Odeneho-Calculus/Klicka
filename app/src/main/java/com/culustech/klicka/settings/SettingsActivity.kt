package com.culustech.klicka.settings

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.culustech.klicka.R
import com.culustech.klicka.data.ClickPointStore
import com.culustech.klicka.data.OverlaySettings

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val store = ClickPointStore(this)
        val settings = store.loadSettings()

        val sizeEt = findViewById<EditText>(R.id.et_size)
        val opacityEt = findViewById<EditText>(R.id.et_opacity)
        val colorEt = findViewById<EditText>(R.id.et_color)
        sizeEt.setText(settings.sizeDp.toString())
        opacityEt.setText(settings.opacity.toString())
        colorEt.setText(settings.color.toString())

        findViewById<Button>(R.id.btn_save).setOnClickListener {
            val newSettings = OverlaySettings(
                sizeDp = sizeEt.text.toString().toIntOrNull() ?: settings.sizeDp,
                opacity = opacityEt.text.toString().toFloatOrNull() ?: settings.opacity,
                color = colorEt.text.toString().toIntOrNull() ?: settings.color
            )
            store.saveSettings(newSettings)
            finish()
        }
    }
}