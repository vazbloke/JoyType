package com.vazbloke.joytype

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import android.os.PowerManager
import androidx.annotation.RequiresApi
import kotlinx.coroutines.channels.Channel

@RequiresApi(Build.VERSION_CODES.P)
@SuppressLint("MissingPermission")
class BluetoothHidTransmitter(private val context: Context, private val onStatusChange: (String, Boolean) -> Unit) : OutputTransmitter {

    override val isHardwareSpoofingRequired = true 

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private var hidDevice: BluetoothHidDevice? = null
    private var connectedHost: BluetoothDevice? = null

    private var originalDeviceName: String? = null 
    private var wakeLock: PowerManager.WakeLock? = null // THE CPU KEEPALIVE

    var isRegistered = false
        private set

    // THE FIX: A perfectly ordered FIFO Queue to prevent overlapping letters!
    private val transmitChannel = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JoyType::BluetoothHID")

        // Start the dedicated typing thread
        CoroutineScope(Dispatchers.IO).launch {
            for (action in transmitChannel) {
                action.invoke()
            }
        }
    }

    // THE FIX: Composite HID Descriptor (Keyboard + Gamepad)
    private val HID_REPORT_DESC = byteArrayOf(
        // --- KEYBOARD (Report ID 1) ---
        0x05, 0x01, 0x09, 0x06, 0xA1.toByte(), 0x01, 
        0x85.toByte(), 0x01, // Report ID: 1
        0x05, 0x07, 0x19, 0xE0.toByte(), 0x29, 0xE7.toByte(), 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95.toByte(), 0x08,
        0x81.toByte(), 0x02, 0x95.toByte(), 0x01, 0x75, 0x08, 0x81.toByte(), 0x01, 0x95.toByte(), 0x05,
        0x75, 0x01, 0x05, 0x08, 0x19, 0x01, 0x29, 0x05, 0x91.toByte(), 0x02,
        0x95.toByte(), 0x01, 0x75, 0x03, 0x91.toByte(), 0x01, 0x95.toByte(), 0x06, 0x75, 0x08,
        0x15, 0x00, 0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81.toByte(), 0x00,
        0xC0.toByte(),

        // --- GAMEPAD (Report ID 2) ---
        0x05, 0x01, 0x09, 0x05, 0xA1.toByte(), 0x01,
        0x85.toByte(), 0x02, // Report ID: 2
        0x05, 0x09, 0x19, 0x01, 0x29, 0x10, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95.toByte(), 0x10,
        0x81.toByte(), 0x02, // 16 Buttons (2 Bytes)
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x32, 0x09, 0x35, 
        0x15, 0x81.toByte(), 0x25, 0x7F, 0x75, 0x08, 0x95.toByte(), 0x04,
        0x81.toByte(), 0x02, // 4 Axes (4 Bytes)
        0xC0.toByte()
    )

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            isRegistered = registered
            if (registered) onStatusChange("Ready to pair. Discoverable.", false)
            else onStatusChange("Disconnected", false)
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedHost = device
                    wakeLock?.acquire() // THE FIX: Prevent CPU Doze when screen is off!
                    onStatusChange("Connected to ${device.name ?: "Device"}", true)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectedHost = null
                    if (wakeLock?.isHeld == true) wakeLock?.release() // Let CPU sleep
                    onStatusChange("Ready to pair. Discoverable.", false)
                }
            }
        }
    }

    fun start() {
        if (bluetoothAdapter?.isEnabled != true) {
            onStatusChange("Bluetooth is disabled", false)
            return
        }

        // THE FIX: Save the user's phone name and rename the chip for pairing!
        try {
            originalDeviceName = bluetoothAdapter.name
            bluetoothAdapter.name = "JoyType Handheld"
        } catch (e: Exception) { e.printStackTrace() } // Safety catch for locked-down OEMs

        bluetoothAdapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    hidDevice = proxy as BluetoothHidDevice
                    // Inside start(), change the SDP initialization to this:
                    val sdp = BluetoothHidDeviceAppSdpSettings(
                        "JoyType Handheld", 
                        "Universal Input Engine", 
                        "Vazbloke", 
                        0x48.toByte(), // THE FIX: 0x48 officially tells the OS: "I am a Keyboard AND a Gamepad"
                        HID_REPORT_DESC
                    )
                    hidDevice?.registerApp(sdp, null, null, context.mainExecutor, hidCallback)
                }
            }
            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.HID_DEVICE) hidDevice = null
            }
        }, BluetoothProfile.HID_DEVICE)
    }

    fun stop() {
        connectedHost?.let { hidDevice?.disconnect(it) }
        hidDevice?.unregisterApp()
        isRegistered = false
        onStatusChange("Disconnected", false)

        // THE FIX: Restore the user's original phone name!
        try {
            originalDeviceName?.let { bluetoothAdapter.name = it }
        } catch (e: Exception) { e.printStackTrace() }

        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    // --- PACKET TRANSMISSION ---
    private fun sendKeyboardReport(modifier: Byte, keycode: Byte) {
        if (connectedHost == null || hidDevice == null) return
        val report = ByteArray(8)
        report[0] = modifier
        report[2] = keycode
        hidDevice?.sendReport(connectedHost, 1, report)
    }

    private fun releaseKeys() {
        sendKeyboardReport(0x00, 0x00)
    }

    override fun sendKeyPress(keyCode: Int, requiresShift: Boolean) {
        val hidMod: Byte = if (requiresShift) 0x02 else 0x00
        val hidKey = androidToHidMap[keyCode] ?: 0x00
        if (hidKey == 0x00.toByte()) return

        transmitChannel.trySend {
            sendKeyboardReport(hidMod, hidKey)
            delay(15) 
            releaseKeys()
            delay(15) // Extra padding between strokes
        }
    }

    override fun commitText(text: String) {
        transmitChannel.trySend {
            for (char in text) {
                val hidMod: Byte = if (requiresShift(char)) 0x02 else 0x00
                val hidKey = charToHidMap[char.lowercaseChar()] ?: 0x00
                if (hidKey != 0x00.toByte()) {
                    sendKeyboardReport(hidMod, hidKey)
                    delay(15)
                    releaseKeys()
                    delay(15)
                }
            }
        }
    }

    override fun deleteSurroundingText(leftLength: Int, rightLength: Int) {
        transmitChannel.trySend {
            for (i in 0 until leftLength) {
                sendKeyboardReport(0x00, 0x2A) 
                delay(15)
                releaseKeys()
                delay(15)
            }
        }
    }

    // BLIND STATE: Over Bluetooth, we cannot read the PC's screen!
    override fun getEditorState(): EditorStateSnapshot? = null
    override fun setSelection(start: Int, end: Int) {}
    override fun beginBatchEdit() {}
    override fun endBatchEdit() {}
    override fun performEditorAction(actionId: Int) {}
    override fun performContextMenuAction(id: Int) {}

    // --- MAPPING DICTIONARIES ---
    private fun requiresShift(char: Char): Boolean {
        if (char.isUpperCase()) return true
        return char in listOf('?', '!', '@', '_', ':', '"', '(', ')', '&', '#', '%', '*', '+', '<', '>', '$', '~', '{', '}', '|', '^')
    }

    private val androidToHidMap = mapOf(
        KeyEvent.KEYCODE_DPAD_UP to 0x52.toByte(),
        KeyEvent.KEYCODE_DPAD_DOWN to 0x51.toByte(),
        KeyEvent.KEYCODE_DPAD_LEFT to 0x50.toByte(),
        KeyEvent.KEYCODE_DPAD_RIGHT to 0x4F.toByte(),
        KeyEvent.KEYCODE_DEL to 0x2A.toByte(),
        KeyEvent.KEYCODE_ENTER to 0x28.toByte(),
        KeyEvent.KEYCODE_SPACE to 0x2C.toByte()
    )

    private val charToHidMap = mapOf(
        'a' to 0x04.toByte(), 'b' to 0x05.toByte(), 'c' to 0x06.toByte(), 'd' to 0x07.toByte(),
        'e' to 0x08.toByte(), 'f' to 0x09.toByte(), 'g' to 0x0A.toByte(), 'h' to 0x0B.toByte(),
        'i' to 0x0C.toByte(), 'j' to 0x0D.toByte(), 'k' to 0x0E.toByte(), 'l' to 0x0F.toByte(),
        'm' to 0x10.toByte(), 'n' to 0x11.toByte(), 'o' to 0x12.toByte(), 'p' to 0x13.toByte(),
        'q' to 0x14.toByte(), 'r' to 0x15.toByte(), 's' to 0x16.toByte(), 't' to 0x17.toByte(),
        'u' to 0x18.toByte(), 'v' to 0x19.toByte(), 'w' to 0x1A.toByte(), 'x' to 0x1B.toByte(),
        'y' to 0x1C.toByte(), 'z' to 0x1D.toByte(),
        '1' to 0x1E.toByte(), '2' to 0x1F.toByte(), '3' to 0x20.toByte(), '4' to 0x21.toByte(),
        '5' to 0x22.toByte(), '6' to 0x23.toByte(), '7' to 0x24.toByte(), '8' to 0x25.toByte(),
        '9' to 0x26.toByte(), '0' to 0x27.toByte(),
        ' ' to 0x2C.toByte(), '\n' to 0x28.toByte(),
        '-' to 0x2D.toByte(), '_' to 0x2D.toByte(),
        '=' to 0x2E.toByte(), '+' to 0x2E.toByte(),
        '[' to 0x2F.toByte(), '{' to 0x2F.toByte(),
        ']' to 0x30.toByte(), '}' to 0x30.toByte(),
        '\\' to 0x31.toByte(), '|' to 0x31.toByte(),
        ';' to 0x33.toByte(), ':' to 0x33.toByte(),
        '\'' to 0x34.toByte(), '"' to 0x34.toByte(),
        '`' to 0x35.toByte(), '~' to 0x35.toByte(),
        ',' to 0x36.toByte(), '<' to 0x36.toByte(),
        '.' to 0x37.toByte(), '>' to 0x37.toByte(),
        '/' to 0x38.toByte(), '?' to 0x38.toByte(),
        '!' to 0x1E.toByte(), '@' to 0x1F.toByte(), '#' to 0x20.toByte(), '$' to 0x21.toByte(),
        '%' to 0x22.toByte(), '^' to 0x23.toByte(), '&' to 0x24.toByte(), '*' to 0x25.toByte(),
        '(' to 0x26.toByte(), ')' to 0x27.toByte()
    )

    override fun sendGamepadState(buttons: Int, leftX: Float, leftY: Float, rightX: Float, rightY: Float) {
        if (connectedHost == null || hidDevice == null) return
        
        val report = ByteArray(6)
        report[0] = (buttons and 0xFF).toByte()
        report[1] = ((buttons shr 8) and 0xFF).toByte()
        report[2] = (leftX * 127).toInt().toByte()  // X
        report[3] = (leftY * 127).toInt().toByte()  // Y
        report[4] = (rightX * 127).toInt().toByte() // Z
        report[5] = (rightY * 127).toInt().toByte() // Rz

        hidDevice?.sendReport(connectedHost, 2, report) // Sent instantly on ID 2!
    }
}