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
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
            var isDarkTheme by remember { mutableStateOf(true) }
            var isNotificationsEnabled by remember { mutableStateOf(true) }
            var currentScreen by remember { mutableStateOf("MAIN") }

            FluidAppTheme(darkTheme = isDarkTheme) {
                Crossfade(targetState = currentScreen, animationSpec = tween(400), label = "screen_transition") { screen ->
                    when (screen) {
                        "MAIN" -> FluidSosScreen(
                            status = connectionStatus.value,
                            isReady = isReadyToMessage.value,
                            coords = currentCoords.value,
                            logs = activityLogs,
                            onSendMessage = { text -> sendPayload("MSG", text) },
                            onNavigateSettings = { currentScreen = "SETTINGS" }
                        )
                        "SETTINGS" -> SettingsScreen(
                            isDarkTheme = isDarkTheme,
                            onThemeToggle = { isDarkTheme = it },
                            isNotificationsEnabled = isNotificationsEnabled,
                            onNotificationsToggle = { isNotificationsEnabled = it },
                            onBack = { currentScreen = "MAIN" }
                        )
                    }
                }
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
                    log("GPS unavailable")
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

    @Suppress("DEPRECATION")
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

    @Suppress("DEPRECATION")
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FluidSosScreen(
    status: String, isReady: Boolean, coords: String, logs: List<String>,
    onSendMessage: (String) -> Unit, onNavigateSettings: () -> Unit
) {
    var manualText by remember { mutableStateOf("") }
    val quickMessages = listOf("🚨 Medical", "🏚️ Trapped", "💧 Water", "✅ Safe")

    val bgColor by animateColorAsState(MaterialTheme.colorScheme.background, tween(500), label = "bg")
    val surfaceColor by animateColorAsState(MaterialTheme.colorScheme.surface, tween(500), label = "surface")
    val textColor by animateColorAsState(MaterialTheme.colorScheme.onBackground, tween(500), label = "text")

    Scaffold(
        containerColor = bgColor,
        topBar = {
            TopAppBar(
                title = { Text("Off-Grid SOS", fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp) },
                actions = {
                    IconButton(onClick = onNavigateSettings) {
                        Text("⚙️", fontSize = 24.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, titleContentColor = textColor)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = surfaceColor.copy(alpha = 0.9f)),
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("NETWORK STATUS", color = textColor.copy(alpha = 0.5f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isReady) Color(0xFF00E676).copy(alpha = 0.2f) else Color(0xFFFF5252).copy(alpha = 0.2f),
                            modifier = Modifier.padding(4.dp)
                        ) {
                            Text(
                                text = if (isReady) "ARMED" else "LINKING",
                                color = if (isReady) Color(0xFF00C853) else Color(0xFFFF5252),
                                fontWeight = FontWeight.Black, fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(status, color = textColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("GPS: $coords", color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                quickMessages.take(2).forEach { msg ->
                    Button(onClick = { onSendMessage(msg) }, enabled = isReady, modifier = Modifier.weight(1f).height(55.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = surfaceColor, contentColor = textColor)) {
                        Text(msg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                quickMessages.takeLast(2).forEach { msg ->
                    Button(onClick = { onSendMessage(msg) }, enabled = isReady, modifier = Modifier.weight(1f).height(55.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = surfaceColor, contentColor = textColor)) {
                        Text(msg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            OutlinedTextField(
                value = manualText, onValueChange = { manualText = it }, enabled = isReady,
                modifier = Modifier.fillMaxWidth(), placeholder = { Text("Custom message...", color = textColor.copy(alpha = 0.4f)) },
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = textColor, unfocusedTextColor = textColor,
                    focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = textColor.copy(alpha = 0.1f),
                    focusedContainerColor = surfaceColor, unfocusedContainerColor = surfaceColor
                )
            )

            Button(
                onClick = { onSendMessage(manualText.ifBlank { "MANUAL_SOS" }); manualText = "" },
                enabled = isReady, modifier = Modifier.fillMaxWidth().height(60.dp), shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, disabledContainerColor = surfaceColor)
            ) {
                Text(if (isReady) "TRANSMIT SOS" else "WAITING FOR RADIO...", color = if (isReady) Color.White else textColor.copy(alpha = 0.3f), fontWeight = FontWeight.Black, fontSize = 16.sp, letterSpacing = 1.sp)
            }

            Box(modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(24.dp)).background(surfaceColor.copy(alpha = 0.5f)).border(1.dp, textColor.copy(alpha = 0.05f), RoundedCornerShape(24.dp)).padding(16.dp)) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(logs) { entry ->
                        Text(entry, color = if (entry.contains("TX Success")) Color(0xFF00C853) else textColor.copy(alpha = 0.6f), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    isDarkTheme: Boolean, onThemeToggle: (Boolean) -> Unit,
    isNotificationsEnabled: Boolean, onNotificationsToggle: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    val bgColor by animateColorAsState(MaterialTheme.colorScheme.background, tween(500), label = "bg_settings")
    val surfaceColor by animateColorAsState(MaterialTheme.colorScheme.surface, tween(500), label = "surface_settings")
    val textColor by animateColorAsState(MaterialTheme.colorScheme.onBackground, tween(500), label = "text_settings")

    Scaffold(
        containerColor = bgColor,
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("⬅️", fontSize = 24.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, titleContentColor = textColor)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = surfaceColor),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Dark Mode", color = textColor, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                        Switch(checked = isDarkTheme, onCheckedChange = onThemeToggle, colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary, checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)))
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), color = textColor.copy(alpha = 0.1f))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Enable Notifications", color = textColor, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                        Switch(checked = isNotificationsEnabled, onCheckedChange = onNotificationsToggle, colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary, checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)))
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "Made By R.Harshvanth Kumar",
                color = textColor.copy(alpha = 0.4f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun FluidAppTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    val primaryColor = Color(0xFFFF1744)
    val darkColors = darkColorScheme(
        background = Color(0xFF0F0F14), surface = Color(0xFF1C1C24),
        primary = primaryColor, onBackground = Color.White
    )
    val lightColors = lightColorScheme(
        background = Color(0xFFF2F2F7), surface = Color(0xFFFFFFFF),
        primary = primaryColor, onBackground = Color(0xFF1C1C24)
    )
    MaterialTheme(colorScheme = if (darkTheme) darkColors else lightColors, content = content)
}
