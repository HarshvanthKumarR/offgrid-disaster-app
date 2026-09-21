package com.example.offgridsos

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    companion object {
        val BLE_SERVICE_UUID: UUID = UUID.fromString("12345678-1234-1234-1234-123456789012")
        val BLE_CHAR_UUID: UUID = UUID.fromString("87654321-4321-4321-4321-210987654321")
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var targetCharacteristic: BluetoothGattCharacteristic? = null

    private val connectionStatus = mutableStateOf("Initializing...")
    private val isReadyToMessage = mutableStateOf(false)
    private val currentCoords = mutableStateOf("Acquiring GPS...")
    private val activityLogs = mutableStateListOf<String>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) { initSystem() }
        else { Toast.makeText(this, "Permissions required", Toast.LENGTH_LONG).show() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        setContent {
            IsolatedDarkTheme {
                IsolatedSosScreen(
                    status = connectionStatus.value,
                    isReady = isReadyToMessage.value,
                    coords = currentCoords.value,
                    logs = activityLogs,
                    onSendMessage = { text -> sendPayload("MSG", text) }
                )
            }
        }
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val needed = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT
        ).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray()) else initSystem()
    }

    private fun initSystem() {
        fetchLocation { startBleScan() }
    }

    @SuppressLint("MissingPermission")
    private fun fetchLocation(onComplete: () -> Unit) {
        connectionStatus.value = "Acquiring GPS coordinates..."
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { loc ->
                if (loc != null) {
                    currentCoords.value = "${loc.latitude},${loc.longitude}"
                    log("GPS Locked: ${currentCoords.value}")
                } else {
                    currentCoords.value = "0.0,0.0"
                    log("GPS unavailable; defaulting to 0.0,0.0")
                }
                onComplete()
            }
            .addOnFailureListener {
                currentCoords.value = "0.0,0.0"
                log("GPS Failed")
                onComplete()
            }
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        connectionStatus.value = "Scanning for ESP32 Bridge..."
        log("BLE Scan started...")

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                val device = result?.device
                if (device?.name == "LoRa_Bridge_Sender") {
                    scanner.stopScan(this)
                    connectionStatus.value = "ESP32 Detected. Connecting..."
                    log("Found LoRa_Bridge_Sender. Connecting...")
                    connectToDevice(device)
                }
            }
        }
        scanner.startScan(scanCallback)
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        bluetoothGatt = device.connectGatt(this, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    log("GATT Connected. Discovering Services...")
                    gatt?.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    runOnUiThread {
                        connectionStatus.value = "Disconnected"
                        isReadyToMessage.value = false
                        log("Disconnected from ESP32")
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val service = gatt?.getService(BLE_SERVICE_UUID)
                    targetCharacteristic = service?.getCharacteristic(BLE_CHAR_UUID)

                    if (targetCharacteristic != null) {
                        runOnUiThread {
                            log("BLE Ready. Transmitting Initial Coords...")
                            sendPayload("INIT", "")
                            isReadyToMessage.value = true
                            connectionStatus.value = "Armed & Connected"
                        }
                    }
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun sendPayload(type: String, message: String) {
        val char = targetCharacteristic
        val gatt = bluetoothGatt
        if (char == null || gatt == null) return

        val timestampMs = System.currentTimeMillis()
        val payload = if (type == "INIT") "INIT|${currentCoords.value}|$timestampMs"
        else "MSG|${currentCoords.value}|$timestampMs|$message"

        char.value = payload.toByteArray(Charsets.UTF_8)
        if (gatt.writeCharacteristic(char)) log("TX Success [$type]: $payload")
        else log("TX Failed [$type]")
    }

    private fun log(entry: String) {
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        val time = sdf.format(Date())
        runOnUiThread { activityLogs.add(0, "[$time] $entry") }
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothGatt?.close()
    }
}

@Composable
fun IsolatedSosScreen(status: String, isReady: Boolean, coords: String, logs: List<String>, onSendMessage: (String) -> Unit) {
    var manualText by remember { mutableStateOf("") }
    val quickMessages = listOf("🚨 Medical Emergency", "🏚️ Trapped", "💧 Need Water", "✅ Safe")

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF121212)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("STATUS", color = Color(0xFF888888), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (isReady) "ARMED & READY" else "LINKING...",
                        color = if (isReady) Color(0xFF00E676) else Color(0xFFFF5252),
                        fontWeight = FontWeight.Black, fontSize = 12.sp
                    )
                }
                Text(status, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                Text("COORDINATES: $coords", color = Color(0xFFBB86FC), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            quickMessages.take(2).forEach { msg ->
                Button(onClick = { onSendMessage(msg) }, enabled = isReady, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C2C))) {
                    Text(msg, color = Color.White, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            quickMessages.takeLast(2).forEach { msg ->
                Button(onClick = { onSendMessage(msg) }, enabled = isReady, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C2C))) {
                    Text(msg, color = Color.White, fontSize = 11.sp, maxLines = 1)
                }
            }
        }

        OutlinedTextField(
            value = manualText, onValueChange = { manualText = it }, enabled = isReady,
            modifier = Modifier.fillMaxWidth(), placeholder = { Text("Optional message...", color = Color(0xFF555555)) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFFFF5252), unfocusedBorderColor = Color(0xFF333333),
                focusedContainerColor = Color(0xFF1E1E1E), unfocusedContainerColor = Color(0xFF1E1E1E)
            )
        )

        Button(
            onClick = { onSendMessage(manualText.ifBlank { "MANUAL_SOS" }); manualText = "" },
            enabled = isReady, modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF1744), disabledContainerColor = Color(0xFF333333))
        ) {
            Text(if (isReady) "SEND OFF-GRID SOS" else "WAITING FOR SYNC...", color = if (isReady) Color.White else Color(0xFF777777), fontWeight = FontWeight.Bold)
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f).background(Color(0xFF0A0A0A), RoundedCornerShape(8.dp)).border(1.dp, Color(0xFF222222), RoundedCornerShape(8.dp)).padding(8.dp)) {
            LazyColumn {
                items(logs) { entry ->
                    Text(entry, color = if (entry.contains("TX Success")) Color(0xFF00E676) else Color(0xFFCCCCCC), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun IsolatedDarkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(background = Color(0xFF121212), surface = Color(0xFF1E1E1E), primary = Color(0xFFFF1744)),
        content = content
    )
}
