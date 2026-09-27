package com.example.livemic

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var engine: LoopbackEngine
    private lateinit var startBtn: Button
    private lateinit var status: TextView
    private lateinit var scoBox: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        engine = LoopbackEngine(this)

        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
        }

        val title = TextView(this).apply { text = "Live Mic"; textSize = 30f }
        scoBox = CheckBox(this).apply {
            text = "Bluetooth low-delay mode\n(uses call audio – less delay, lower sound quality)"
        }
        val gainLabel = TextView(this).apply { text = "Volume boost: 100%"; textSize = 16f }
        val gainBar = SeekBar(this).apply {
            max = 400
            progress = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    engine.gain = p / 100f
                    gainLabel.text = "Volume boost: $p%"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        startBtn = Button(this).apply {
            text = "START"
            textSize = 24f
            setOnClickListener { toggle() }
        }
        status = TextView(this).apply {
            text = "Connect your Bluetooth speaker first, then press START."
            textSize = 15f
        }

        listOf(title, scoBox, gainLabel, gainBar, startBtn, status).forEach {
            root.addView(it, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = pad / 2 })
        }
        setContentView(root)
    }

    private fun toggle() {
        if (engine.isRunning) {
            engine.stop()
            startBtn.text = "START"
            scoBox.isEnabled = true
            status.text = "Stopped."
            return
        }
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) needed += Manifest.permission.BLUETOOTH_CONNECT
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
        else startEngine()
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(rc, perms, results)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startEngine()
        } else {
            status.text = "Microphone permission is needed."
        }
    }

    private fun startEngine() {
        try {
            status.text = engine.start(scoBox.isChecked)
            startBtn.text = "STOP"
            scoBox.isEnabled = false
        } catch (e: Exception) {
            status.text = "Could not start: ${e.message}"
        }
    }

    override fun onDestroy() {
        engine.stop()
        super.onDestroy()
    }
}
