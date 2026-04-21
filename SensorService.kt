package com.example.mhealth

import android.Manifest
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.sqrt

class SensorService : Service(), SensorEventListener {

    private val TAG = "FallDetection"

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var stepCounterSensor: Sensor? = null
    
    private lateinit var prefs: SharedPreferences
    private lateinit var database: AppDatabase
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var bestLocation: Location? = null

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    // For Persistent Step Counting
    private var initialStepCount = -1
    private var lastUpdateDay = -1
    private var currentSessionSteps = 0

    // --- ENHANCED FALL DETECTION SETTINGS ---
    private val IS_FALL_DETECTION_ENABLED = true 
    
    private val FALL_STABILITY_THRESHOLD = 1.5 // Jitter to consider "stationary"
   
   
    
    
    private var lastFallDetectedTime: Long = 0
   
    // ----------------------------------------
    
    private var lastGForceBroadcastTime: Long = 0
    private val GFORCE_UPDATE_INTERVAL = 500L

    // SEDENTARY ALERT SETTINGS
    private var lastMovementTime: Long = System.currentTimeMillis()
    private val SEDENTARY_THRESHOLD = 60 * 60 * 1000L // 1 hour in milliseconds
    private var lastSedentaryAlertTime: Long = 0
    
    // TABLE DETECTION (STABILITY CHECK)
    private var lastGravityValue: Double = 0.0
    private var isPhoneStationaryOnTable: Boolean = false
    private val STATIONARY_JITTER_THRESHOLD = 0.05 // Very low jitter means it's on a table

    private val CHANNEL_ID = "mHealth_Alerts"

    // --- ACCURATE LOCATION CALLBACK ---
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            for (location in locationResult.locations) {
                // Keep the location with the best accuracy (lowest error meters)
                if (bestLocation == null || location.accuracy < bestLocation!!.accuracy) {
                    bestLocation = location
                    Log.d(TAG, "New Best GPS Fix: ${location.latitude}, ${location.longitude} Accuracy: ${location.accuracy}m")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SensorService Created - Monitoring Started")
        
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        database = AppDatabase.getDatabase(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        prefs = getSharedPreferences("mHealth_Steps", Context.MODE_PRIVATE)
        initialStepCount = prefs.getInt("initial_base", -1)
        lastUpdateDay = prefs.getInt("last_day", -1)
        currentSessionSteps = prefs.getInt("current_steps", 0)

        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }

        // Only register step counter if permission is granted
        val activityRecognitionPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        if (activityRecognitionPermission) {
            stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            stepCounterSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        } else {
            Log.w(TAG, "Activity Recognition permission not granted. Step counter disabled.")
        }

        createNotificationChannel()
        startForegroundService()
        startSedentaryMonitor()
    }

    private fun startForegroundService() {
        val notificationIntent = Intent(this, DashboardActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("mHealth Active")
            .setContentText("Monitoring health sensors in the background")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Check if we have the necessary permissions for health type on Android 14+
                val canUseHealthType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
                } else {
                    true
                }

                if (canUseHealthType) {
                    startForeground(100, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
                } else {
                    // Fallback to no type if permission is missing to avoid crash
                    startForeground(100, notification)
                }
            } else {
                startForeground(100, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service: ${e.message}")
            // Final fallback to at least try starting without type
            try {
                startForeground(100, notification)
            } catch (e2: Exception) {
                Log.e(TAG, "Critical error starting foreground service: ${e2.message}")
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "mHealth Alerts"
            val descriptionText = "Notifications for falls, goals, and activity"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showNotification(title: String, message: String, isUrgent: Boolean = false) {
        val intent = if (isUrgent) {
            Intent(this, FallAlertActivity::class.java)
        } else {
            Intent(this, NotificationsActivity::class.java)
        }
        
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            this, if (isUrgent) 1 else 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)

        if (isUrgent) {
            builder.setFullScreenIntent(pendingIntent, true)
        } else {
            builder.setContentIntent(pendingIntent)
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(if (isUrgent) 911 else System.currentTimeMillis().toInt(), builder.build())
    }

    private fun startSedentaryMonitor() {
        serviceScope.launch {
            while (isActive) {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastMovementTime > SEDENTARY_THRESHOLD && !isPhoneStationaryOnTable) {
                    if (currentTime - lastSedentaryAlertTime > SEDENTARY_THRESHOLD) {
                        lastSedentaryAlertTime = currentTime
                        saveSedentaryAlert()
                    }
                }
                delay(5 * 60 * 1000) // Check every 5 minutes
            }
        }
    }

    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun startAccurateLocationTracking() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setWaitForAccurateLocation(true)
            .setMinUpdateIntervalMillis(500)
            .setMaxUpdateDelayMillis(1000)
            .build()

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        Log.d(TAG, "High-Accuracy GPS Warm-up started...")
    }

    private fun stopLocationTracking() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    override fun onSensorChanged(event: SensorEvent?) {
    if (event == null) return

    val currentTime = System.currentTimeMillis()

    if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val gForce = sqrt(x * x + y * y + z * z)

        val jitter = Math.abs(gForce - lastGravityValue)
        isPhoneStationaryOnTable = jitter < STATIONARY_JITTER_THRESHOLD
        lastGravityValue = gForce.toDouble()

        if (currentTime - lastGForceBroadcastTime > GFORCE_UPDATE_INTERVAL) {
            lastGForceBroadcastTime = currentTime

            sendBroadcast(Intent("com.example.mhealth.GFORCE_UPDATE").apply {
                putExtra("gForce", gForce)
                setPackage(packageName)
            })
        }

        if (gForce > 28 && currentTime - lastFallDetectedTime > 3000 && !isPhoneStationaryOnTable) {
            lastFallDetectedTime = currentTime

            Log.d(TAG, "Fall Detected! G-Force: $gForce")

            sendBroadcast(Intent("com.example.mhealth.FALL_DETECTED").apply {
                setPackage(packageName)
            })
        }
    }

    if (event.sensor.type == Sensor.TYPE_STEP_COUNTER) {
        val totalSteps = event.values[0].toInt()
        val currentDay = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)

        if (lastUpdateDay != currentDay) {
            initialStepCount = totalSteps
            currentSessionSteps = 0
            lastUpdateDay = currentDay
            saveToPrefs()
        }

        if (initialStepCount == -1) {
            initialStepCount = totalSteps
            lastUpdateDay = currentDay
            saveToPrefs()
        }

        val calculatedSteps = totalSteps - initialStepCount

        if (calculatedSteps > currentSessionSteps) {
            currentSessionSteps = calculatedSteps
            lastMovementTime = System.currentTimeMillis()
            isPhoneStationaryOnTable = false

            saveToPrefs()
            saveStepsToDatabase(currentSessionSteps)

            sendBroadcast(Intent("com.example.mhealth.STEP_UPDATE").apply {
                putExtra("steps", currentSessionSteps)
                setPackage(packageName)
            })

            if (currentSessionSteps == 10000) {
                saveStepGoalAlert()
            }
        }
    }
}


    private fun saveFallAlert(isUrgent: Boolean = false) {
        val title = "FALL DETECTED!"
        val currentTimeStr = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        val message = "A hard fall was detected at $currentTimeStr. Emergency contacts have been notified."
        
        showNotification(title, message, isUrgent)
        
        serviceScope.launch {
            database.alertDao().insertAlert(
                Alert(
                    type = AlertType.FALL_DETECTED,
                    title = title,
                    message = message,
                    timeAgo = "Just now"
                )
            )
        }
    }

    private fun saveStepGoalAlert() {
        val title = "STEP GOAL REACHED!"
        val message = "Congratulations! You've reached your daily goal of 10,000 steps."
        showNotification(title, message)

        serviceScope.launch {
            database.alertDao().insertAlert(
                Alert(
                    type = AlertType.STEP_GOAL,
                    title = title,
                    message = message,
                    timeAgo = "Just now"
                )
            )
        }
    }

    private fun saveSedentaryAlert() {
        val title = "STRETCH YOUR LEGS!"
        val message = "You've been sitting for over an hour. It's time for a quick walk!"
        showNotification(title, message)

        serviceScope.launch {
            database.alertDao().insertAlert(
                Alert(
                    type = AlertType.SEDENTARY_ALERT,
                    title = title,
                    message = message,
                    timeAgo = "Just now"
                )
            )
        }
    }

    private fun saveToPrefs() {
        prefs.edit().apply {
            putInt("initial_base", initialStepCount)
            putInt("last_day", lastUpdateDay)
            putInt("current_steps", currentSessionSteps)
            apply()
        }
    }

    private fun saveStepsToDatabase(steps: Int) {
        val currentTime = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault()).format(Date())
        serviceScope.launch {
            database.historyDao().insertRecord(
                HistoryRecord(RecordType.STEPS, currentTime, "$steps Steps")
            )
            
            // Sync to Firestore
            syncStepsToFirestore(steps, currentTime)
        }
    }
    
    private fun syncStepsToFirestore(steps: Int, time: String) {
        val user = auth.currentUser ?: return
        val data = mapOf(
            "type" to RecordType.STEPS.name,
            "time" to time,
            "value" to "$steps Steps",
            "timestamp" to System.currentTimeMillis()
        )
        db.collection("users").document(user.uid)
            .collection("history")
            .add(data)
            .addOnSuccessListener {
                Log.d("FirestoreSync", "Steps synced: $steps")
            }
            .addOnFailureListener { e ->
                Log.e("FirestoreSync", "Error syncing steps", e)
            }
    }
    
    fun sendSOSWithLocation() {
        val emergencyPrefs = getSharedPreferences("mHealth_Emergency", Context.MODE_PRIVATE)
        val emergencyNumber = emergencyPrefs.getString("emergency_number", "")

        if (emergencyNumber.isNullOrEmpty()) {
            Log.e(TAG, "emergency number not set. SMS aborted.")
            return
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Location permission not granted for SOS")
            return
        }

        // Use the best location captured during the countdown
        val location = bestLocation
        val locationMsg = if (location != null) {
            "URGENT: Fall detected! My live location: https://www.google.com/maps/search/?api=1&query=${location.latitude},${location.longitude} (Accuracy: ${location.accuracy}m)"
        } else {
            "URGENT: Fall detected! Location could not be pinpointed in time."
        }
        
        Log.d(TAG, "Sending SOS SMS to $emergencyNumber: $locationMsg")
        
        try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                this.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            
            smsManager.sendTextMessage(emergencyNumber, null, locationMsg, null, null)
            Log.d(TAG, "SOS SMS Sent successfully with high precision.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS", e)
        } finally {
            stopLocationTracking() // Stop GPS tracking after SMS is sent
            bestLocation = null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "com.example.mhealth.ACTION_TRIGGER_SOS") {
            sendSOSWithLocation()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        super.onDestroy()
        stopLocationTracking()
        sensorManager.unregisterListener(this)
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
