package com.vazbloke.joytype

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private lateinit var switchBluetooth: SwitchMaterial
    private lateinit var tvBtStatus: TextView
    private lateinit var cardBluetooth: MaterialCardView
    private lateinit var etSandbox: EditText

    private lateinit var localTransmitter: LocalActivityTransmitter
    private lateinit var bluetoothTransmitter: BluetoothHidTransmitter
    private lateinit var frontend: JoyTypeFrontend

    private lateinit var llBtUtilities: View
    private lateinit var btnMacSync: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Bind UI Elements
        switchBluetooth = findViewById(R.id.switch_bluetooth) // THE FIX: Add this line back!
        tvBtStatus = findViewById(R.id.tv_bt_status)
        cardBluetooth = findViewById(R.id.card_bluetooth)
        etSandbox = findViewById(R.id.et_sandbox)
        etSandbox.showSoftInputOnFocus = false // Suppress Android Keyboard

        llBtUtilities = findViewById(R.id.ll_bt_utilities)
        btnMacSync = findViewById(R.id.btn_mac_sync)


        val toggleGroup = findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggle_group_mode)
        val tvModeTitle: TextView = findViewById(R.id.tv_mode_title)
        val tvModeDesc: TextView = findViewById(R.id.tv_mode_desc)
        val keyboardContainer: View = findViewById(R.id.keyboard_container)

        // 2. Initialize Local Transmitter & Frontend FIRST!
        localTransmitter = LocalActivityTransmitter(etSandbox)
        frontend = JoyTypeFrontend(
            context = this,
            view = keyboardContainer, 
            transmitter = localTransmitter,
            onHideKeyboard = { finish() }
        )

        val updateModeText = { mode: HardwareMode ->
            when (mode) {
                HardwareMode.KEYBOARD -> {
                    tvModeTitle.text = "Keyboard Mode"
                    tvModeDesc.text = "JoyType active. Hold M1+M2 to cycle."
                    keyboardContainer.visibility = View.VISIBLE
                }
                HardwareMode.GAMEPAD -> {
                    tvModeTitle.text = "Gamepad Mode"
                    tvModeDesc.text = "Hardware controls active."
                    keyboardContainer.visibility = View.GONE
                }
                HardwareMode.MOUSE -> {
                    tvModeTitle.text = "Mouse Mode"
                    tvModeDesc.text = "R-Stick to move. L1/R1 to click."
                    keyboardContainer.visibility = View.GONE
                }
            }
        }

        // Listener for when the user physically clicks the UI buttons
        val toggleListener = com.google.android.material.button.MaterialButtonToggleGroup.OnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val newMode = when (checkedId) {
                    R.id.btn_mode_gp -> HardwareMode.GAMEPAD
                    R.id.btn_mode_ms -> HardwareMode.MOUSE
                    else -> HardwareMode.KEYBOARD
                }
                frontend.controller.hardwareMode = newMode
                updateModeText(newMode)
            }
        }

        // Sync UI when hardware buttons (M1+M2) toggle the mode behind the scenes
        frontend.onHardwareModeChanged = { mode ->
            runOnUiThread {
                toggleGroup.removeOnButtonCheckedListener(toggleListener) // Prevent infinite loop
                when (mode) {
                    HardwareMode.KEYBOARD -> toggleGroup.check(R.id.btn_mode_kb)
                    HardwareMode.GAMEPAD -> toggleGroup.check(R.id.btn_mode_gp)
                    HardwareMode.MOUSE -> toggleGroup.check(R.id.btn_mode_ms)
                }
                updateModeText(mode)
                toggleGroup.addOnButtonCheckedListener(toggleListener)
            }
        }

        toggleGroup.addOnButtonCheckedListener(toggleListener)
        toggleGroup.check(R.id.btn_mode_kb) // Set default UI state on launch

        // 4. Finally, setup the Bluetooth Pipeline
        setupBluetoothToggle()
    }

    override fun onResume() {
        super.onResume()
        if (::frontend.isInitialized) frontend.onResume()
    }

    // --- THE BLUETOOTH PIPELINE ---

    // 1. Permission Check
    private val requestBluetoothPermissionLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.entries.all { it.value }) {
            checkAndEnableBluetooth()
        } else {
            switchBluetooth.isChecked = false
            android.widget.Toast.makeText(this, "Bluetooth permissions required", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // 2. Turn on Bluetooth (If it's off)
    private val enableBluetoothLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            requestDiscoverability()
        } else {
            switchBluetooth.isChecked = false
            android.widget.Toast.makeText(this, "Bluetooth must be turned on to use this mode.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // 3. Become Discoverable to Macs/PCs
    private val discoverabilityLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        // The result code is the duration in seconds (e.g., 300), or RESULT_CANCELED
        if (result.resultCode != android.app.Activity.RESULT_CANCELED) {
            enableBluetoothMode()
        } else {
            // If they deny discoverability, we still start the server in case they are already paired
            enableBluetoothMode()
            android.widget.Toast.makeText(this, "Not discoverable. Can only connect to previously paired devices.", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun checkAndEnableBluetooth() {
        val btManager = getSystemService(android.content.Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        val adapter = btManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            enableBluetoothLauncher.launch(android.content.Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            requestDiscoverability()
        }
    }

    private fun requestDiscoverability() {
        val intent = android.content.Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(android.bluetooth.BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300) // 5 minutes
        }
        discoverabilityLauncher.launch(intent)
    }

    private fun setupBluetoothToggle() {
        bluetoothTransmitter = BluetoothHidTransmitter(this) { statusMsg, isConnected ->
            runOnUiThread {
                tvBtStatus.text = statusMsg
                if (isConnected) {
                    tvBtStatus.setTextColor(android.graphics.Color.parseColor("#4CAF50")) 
                    cardBluetooth.strokeColor = android.graphics.Color.parseColor("#4CAF50")
                } else if (switchBluetooth.isChecked) {
                    tvBtStatus.setTextColor(android.graphics.Color.parseColor("#E6C229")) 
                    cardBluetooth.strokeColor = android.graphics.Color.parseColor("#E6C229")
                }
            }
        }

        switchBluetooth.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) {
                    switchBluetooth.isChecked = false
                    android.widget.Toast.makeText(this, "Bluetooth Mode requires Android 9+.", android.widget.Toast.LENGTH_LONG).show()
                    return@setOnCheckedChangeListener
                }

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        requestBluetoothPermissionLauncher.launch(arrayOf(
                            android.Manifest.permission.BLUETOOTH_CONNECT,
                            android.Manifest.permission.BLUETOOTH_ADVERTISE
                        ))
                        return@setOnCheckedChangeListener
                    }
                }
                
                // Start the chain!
                checkAndEnableBluetooth()
            } else {
                disableBluetoothMode()
            }
        }
    }

    private fun enableBluetoothMode() {
        frontend.setTransmitterInstance(bluetoothTransmitter)
        bluetoothTransmitter.start()
        
        etSandbox.hint = "Bluetooth Mode Active (Keystrokes sent over the air)"
        etSandbox.isEnabled = false
        tvBtStatus.text = "Initializing..."
        tvBtStatus.setTextColor(android.graphics.Color.parseColor("#E6C229"))
        cardBluetooth.strokeColor = android.graphics.Color.parseColor("#E6C229")

        llBtUtilities.visibility = View.VISIBLE // SHOW IT
    }

    private fun disableBluetoothMode() {
        bluetoothTransmitter.stop()
        frontend.setTransmitterInstance(localTransmitter)
        
        etSandbox.hint = "Type here to test local layout..."
        etSandbox.isEnabled = true
        tvBtStatus.text = "Disconnected"
        tvBtStatus.setTextColor(android.graphics.Color.parseColor("#888888"))
        cardBluetooth.strokeColor = android.graphics.Color.parseColor("#333333")

        llBtUtilities.visibility = View.GONE // HIDE IT
    }
    
    override fun onDestroy() {
        super.onDestroy()
        bluetoothTransmitter.stop() // Always clean up the connection when the app closes!
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val isGamepad = KeyEvent.isGamepadButton(keyCode) || keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
        
        if (isGamepad) {
            if (event.action == KeyEvent.ACTION_DOWN) return frontend.onKeyDown(keyCode, event)
            else if (event.action == KeyEvent.ACTION_UP) return frontend.onKeyUp(keyCode, event)
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (frontend.onGenericMotionEvent(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }
}