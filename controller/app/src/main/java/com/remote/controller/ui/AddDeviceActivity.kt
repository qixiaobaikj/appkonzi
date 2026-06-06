package com.remote.controller.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.remote.controller.R
import com.remote.controller.databinding.ActivityAddDeviceBinding

class AddDeviceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddDeviceBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddDeviceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 读取现有的地址
        val prefs = getSharedPreferences("remote_control_prefs", MODE_PRIVATE)
        val currentUrl = prefs.getString("server_url", "ws://192.168.1.100:8080")
        binding.etServerAddress.setText(currentUrl)

        binding.btnSave.setOnClickListener {
            val url = binding.etServerAddress.text.toString().trim()
            if (url.isEmpty()) {
                binding.tilServerAddress.error = getString(R.string.error_empty_address)
                return@setOnClickListener
            }

            if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
                binding.tilServerAddress.error = getString(R.string.error_invalid_address)
                return@setOnClickListener
            }

            binding.tilServerAddress.error = null

            // 保存到本地
            prefs.edit().putString("server_url", url).apply()
            Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK)
            finish()
        }

        binding.btnCancel.setOnClickListener {
            finish()
        }
    }
}
