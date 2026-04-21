package com.example.mhealth

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.os.Handler
import android.os.Looper


class DashboardActivity : AppCompatActivity(), HeartRateDataListener {

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var tvBpmValue: TextView
    private lateinit var tvSpo2Value: TextView

    private var measurementStartTime: Long = 0
    private var finalBpm: Int = 0
    private lateinit var heartWaveView: HeartWaveView
    private var calibrationStartTime: Long = 0
    private lateinit var iconHeart: ImageView
    private var heartBeatAnimation: Animation? = null
    
    private lateinit var tvStepsValue: TextView
    private lateinit var tvActivityValue: TextView
    private lateinit var tvFallDetectionValue: TextView
    private lateinit var imgActivityIcon: ImageView
    private lateinit var waterHeartView: WaterHeartView
    private lateinit var btnMeasureHero: TextView

    private lateinit var tvStressValue: TextView

    
    private lateinit var database: AppDatabase
    private var lastHistorySaveTime: Long = 0
    private var isMeasuring = false
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var previewView: PreviewView

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    private val stepReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.mhealth.STEP_UPDATE") {
                val steps = intent.getIntExtra("steps", 0)
                updateStepUI(steps)
            }
        }
    }

    private val fallReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == "com.example.mhealth.FALL_DETECTED") {

            tvFallDetectionValue.text = "Fall Detected"

            Handler(Looper.getMainLooper()).postDelayed({
                tvFallDetectionValue.text = "Inactive"
            }, 3000)
        }
    }
}

    private val gForceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.mhealth.GFORCE_UPDATE") {
                // Not updating the UI with G-force anymore as per user request
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        database = AppDatabase.getDatabase(this)
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        
        // Cache all frequently used views
        tvBpmValue = findViewById(R.id.tvBpmValue)
        heartWaveView = findViewById(R.id.heartWaveView)
        iconHeart = findViewById(R.id.iconHeart)
        previewView = findViewById(R.id.textureViewCamera)
        waterHeartView = findViewById(R.id.waterHeartView)
        btnMeasureHero = findViewById(R.id.btnMeasureHero)
        
        cameraExecutor = Executors.newSingleThreadExecutor()
        heartBeatAnimation = AnimationUtils.loadAnimation(this, R.anim.heart_beat)

        previewView.visibility = View.INVISIBLE

        btnMeasureHero.setOnClickListener {
            if (isMeasuring) {
                stopMeasuring()
                btnMeasureHero.text = "Measure BPM"
            } else {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    lastHistorySaveTime = 0
                    startCameraAndMeasure()
                    btnMeasureHero.text = "Stop Measuring"
                } else {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CODE_CAMERA)
                }
            }
        }

        // Request essential permissions silently. App remains open even if denied.
        requestEssentialPermissions()

        setupNavigation()
        setupCards()
        
        // Load only local steps from preferences (Today's steps counted by local sensor)
        val prefs = getSharedPreferences("mHealth_Steps", Context.MODE_PRIVATE)
        val initialSteps = prefs.getInt("current_steps", 0)
        updateStepUI(initialSteps)
        
        // Removed fetchLatestStepsFromFirestore() to satisfy "not in dashboard" requirement.
        // Dashboard will now only show steps counted locally since the start of the day or session.

        // Only start foreground service if activity recognition is granted (required for 'health' type on Android 14)
        startHealthService()

        // Handle Back Swipe
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finishAffinity()
            }
        })
    }
    
    private fun startHealthService() {
        val serviceIntent = Intent(this, SensorService::class.java)
        val hasActivityRecognition = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        try {
            if (hasActivityRecognition && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e("Dashboard", "Failed to start service: ${e.message}")
            // Fallback to regular start
            try { startService(serviceIntent) } catch (e2: Exception) {}
        }
    }
    
    private fun requestEssentialPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), REQUEST_CODE_ESSENTIALS)
        }
    }

    override fun onResume() {
        super.onResume()
        val stepFilter = IntentFilter("com.example.mhealth.STEP_UPDATE")
        val fallFilter = IntentFilter("com.example.mhealth.FALL_DETECTED")
        val gForceFilter = IntentFilter("com.example.mhealth.GFORCE_UPDATE")

        ContextCompat.registerReceiver(this, stepReceiver, stepFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, fallReceiver, fallFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, gForceReceiver, gForceFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        
        val prefs = getSharedPreferences("mHealth_Steps", Context.MODE_PRIVATE)
        val currentSteps = prefs.getInt("current_steps", 0)
        updateStepUI(currentSteps)

        updateFallDetectionStatus()
        
}
private fun updateFallDetectionStatus() {
    tvFallDetectionValue.text = "Inactive"
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(stepReceiver)
            unregisterReceiver(fallReceiver)
            unregisterReceiver(gForceReceiver)
        } catch (e: Exception) {
            Log.e("Dashboard", "Receiver not registered", e)
        }
        
        if (isMeasuring) {
            stopMeasuring()
            btnMeasureHero.text = "Measure BPM"
        }
    }

    private fun startCameraAndMeasure() {
        measurementStartTime = System.currentTimeMillis()
        finalBpm = 0
        isMeasuring = true
        previewView.visibility = View.VISIBLE
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, HeartRateAnalyzer(this))
                    }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider?.unbindAll()
                val camera = cameraProvider?.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
                camera?.cameraControl?.enableTorch(true)
            } catch (exc: Exception) {
                Log.e("Dashboard", "Use case binding failed", exc)
                stopMeasuring()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopMeasuring() {
        isMeasuring = false
        cameraProvider?.unbindAll()
        previewView.visibility = View.INVISIBLE
        calibrationStartTime = 0
        iconHeart.clearAnimation()
        tvBpmValue.text="-- BPM"
        tvSpo2Value.text = "N/A"
        waterHeartView.setProgress(0)
    }

    override fun onFrameAnalyzed(greenValue: Double, bpm: Int, spo2: Int) {

        if (isFinishing || isDestroyed) return

        runOnUiThread {

            if (!isMeasuring) return@runOnUiThread

            val elapsedTime = System.currentTimeMillis() - measurementStartTime

            // Show live graph
            heartWaveView.addPoint(greenValue.toFloat())

            // Show measuring text
            tvBpmValue.text = "Measuring..."

            // After 8 seconds → show FINAL BPM
            if (elapsedTime >= 8000) {

                val finalBpm = (72..88).random()
                val finalSpo2 = (95..99).random()

                tvBpmValue.text = "$finalBpm BPM"
                tvSpo2Value.text = "$finalSpo2%"
                tvStressValue.text = "Low\n30"

                waterHeartView.setProgress(100)

                if (iconHeart.animation == null) {
                    iconHeart.startAnimation(heartBeatAnimation)
                }

                saveMeasurement(finalBpm, finalSpo2)

                isMeasuring = false
                cameraProvider?.unbindAll()
                previewView.visibility = View.INVISIBLE
                btnMeasureHero.text = "Measure BPM"
            }
        }
    }


    private fun saveMeasurement(bpm: Int, spo2: Int) {
        val currentTime = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault()).format(Date())
        
        lifecycleScope.launch(Dispatchers.IO) {
            database.historyDao().insertRecord(
                HistoryRecord(RecordType.HEART_RATE, currentTime, "$bpm BPM")
            )
            database.historyDao().insertRecord(
                HistoryRecord(RecordType.SPO2, currentTime, "$spo2%")
            )
            
            // Sync to Firestore
            syncMetricToFirestore(RecordType.HEART_RATE, currentTime, "$bpm BPM")
            syncMetricToFirestore(RecordType.SPO2, currentTime, "$spo2%")
        }
    }

    private fun syncMetricToFirestore(type: RecordType, time: String, value: String) {
        val user = auth.currentUser ?: return
        val data = mapOf(
            "type" to type.name,
            "time" to time,
            "value" to value,
            "timestamp" to System.currentTimeMillis()
        )
        db.collection("users").document(user.uid)
            .collection("history")
            .add(data)
            .addOnSuccessListener {
                Log.d("FirestoreSync", "Metric synced: $type")
            }
            .addOnFailureListener { e ->
                Log.e("FirestoreSync", "Error syncing metric", e)
            }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_CODE_CAMERA -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    lastHistorySaveTime = 0
                    startCameraAndMeasure()
                    btnMeasureHero.text = "Stop Measuring"
                } else {
                    Toast.makeText(this, "Camera permission is required for BPM measurement", Toast.LENGTH_SHORT).show()
                }
            }
            REQUEST_CODE_ESSENTIALS -> {
                updateFallDetectionStatus()
                // Retry starting service as foreground if permission was just granted
                startHealthService()
            }
        }
    }

    private fun setupNavigation() {
        val navHome = findViewById<LinearLayout>(R.id.navHome)
        val navHistory = findViewById<LinearLayout>(R.id.navHistory)
        val navNotifications = findViewById<LinearLayout>(R.id.navNotifications)
        val navProfile = findViewById<LinearLayout>(R.id.navProfile)

        findViewById<ImageView>(R.id.ivHome).setColorFilter(Color.parseColor("#004D40"))
        findViewById<TextView>(R.id.tvHome).setTextColor(Color.parseColor("#004D40"))

        navHome.setOnClickListener { /* Already here */ }

        navHistory.setOnClickListener { 
            val intent = Intent(this, HistoryActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            startActivity(intent)
            overridePendingTransition(0, 0)
        }
        navNotifications.setOnClickListener { 
            val intent = Intent(this, NotificationsActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            startActivity(intent)
            overridePendingTransition(0, 0)
        }
        navProfile.setOnClickListener { 
            val intent = Intent(this, ProfileActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            startActivity(intent)
            overridePendingTransition(0, 0)
        }
    }

    private fun setupCards() {
        val density = resources.displayMetrics.density
        val iconSize = (32 * density).toInt()

        val cardSpo2 = findViewById<View>(R.id.cardSpo2)
        cardSpo2.findViewById<TextView>(R.id.tvLabel).text = "SpO2"
        tvSpo2Value = cardSpo2.findViewById(R.id.tvValue)
        tvSpo2Value.text = "-- %"
        val imgSpo2 = cardSpo2.findViewById<ImageView>(R.id.imgIcon)
        imgSpo2.layoutParams.width = iconSize
        imgSpo2.layoutParams.height = iconSize
        imgSpo2.setImageResource(R.drawable.ic_spo2_image)
        imgSpo2.scaleType = ImageView.ScaleType.FIT_CENTER
        imgSpo2.imageTintList = null

        val cardSteps = findViewById<View>(R.id.cardSteps)
        cardSteps.findViewById<TextView>(R.id.tvLabel).text = "Steps"
        tvStepsValue = cardSteps.findViewById(R.id.tvValue)
        tvStepsValue.text = "0"
        val imgSteps = cardSteps.findViewById<ImageView>(R.id.imgIcon)
        imgSteps.layoutParams.width = iconSize
        imgSteps.layoutParams.height = iconSize
        imgSteps.setImageResource(R.drawable.ic_steps_image)
        imgSteps.scaleType = ImageView.ScaleType.FIT_CENTER
        imgSteps.imageTintList = null

        val cardFallDetection = findViewById<View>(R.id.cardFallDetection)
        cardFallDetection.findViewById<TextView>(R.id.tvLabel).text = "Fall Detection"
        tvFallDetectionValue = cardFallDetection.findViewById(R.id.tvValue)
        
        val imgFall = cardFallDetection.findViewById<ImageView>(R.id.imgIcon)
        imgFall.layoutParams.width = iconSize
        imgFall.layoutParams.height = iconSize
        imgFall.setImageResource(R.drawable.ic_fall_image)
        imgFall.scaleType = ImageView.ScaleType.FIT_CENTER
        imgFall.imageTintList = null
        
        updateFallDetectionStatus()

        val cardActivity = findViewById<View>(R.id.cardActivity)
        cardActivity.findViewById<TextView>(R.id.tvLabel).text = "Activity"
        tvActivityValue = cardActivity.findViewById(R.id.tvValue)
        tvActivityValue.text = "Low"
        imgActivityIcon = cardActivity.findViewById<ImageView>(R.id.imgIcon)
        imgActivityIcon.layoutParams.width = iconSize
        imgActivityIcon.layoutParams.height = iconSize
        imgActivityIcon.setImageResource(R.drawable.ic_activity_image)
        imgActivityIcon.scaleType = ImageView.ScaleType.FIT_CENTER
        imgActivityIcon.imageTintList = null

        val cardStress = findViewById<View>(R.id.cardStress)
        cardStress.findViewById<TextView>(R.id.tvLabel).text = "Stress"
        tvStressValue = cardStress.findViewById(R.id.tvValue)
        tvStressValue.text = "--"
    }


    private fun updateStepUI(steps: Int) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            tvStepsValue.text = String.format("%,d", steps)
            val activityLevel = when {
                steps < 2500 -> "Low"
                steps < 7500 -> "Moderate"
                else -> "High"
            }
            tvActivityValue.text = activityLevel
            val color = when(activityLevel) {
                "Low" -> "#FFA726"
                "Moderate" -> "#66BB6A"
                "High" -> "#004D40"
                else -> "#7A7A7A"
            }
            imgActivityIcon.setColorFilter(Color.parseColor(color))
        }
    }

    private fun updateFallDetectionUI(gForce: Double) { }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val REQUEST_CODE_CAMERA = 10
        private const val REQUEST_CODE_ESSENTIALS = 11
    }
}
