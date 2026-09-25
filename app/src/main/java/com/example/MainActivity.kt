package com.example

import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.example.databinding.ActivityMainBinding
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot

class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding

    // Firebase (null-safe)
    private var auth: FirebaseAuth? = null
    private var database: FirebaseDatabase? = null
    private var channelRef: DatabaseReference? = null
    private var connectedRef: DatabaseReference? = null
    private var channelListener: ValueEventListener? = null
    private var connectedListener: ValueEventListener? = null

    companion object {
        private const val TAG = "MainActivity"
        const val DATABASE_URL = "https://chat-7305d-default-rtdb.firebaseio.com"
        const val PROJECT_ID = "chat-7305d"
    }

    // Navigation & State
    private var isGatewayShowing = true

    // Gesture detection
    private var isHolding = false
    private var isHoldCompleted = false
    private var holdWindowExpiryTime = 0L
    private var startTouchX = 0f
    private var startTouchY = 0f

    private val holdDurationMs = 3000L
    private var touchSlopPx = 0f
    private var swipeThresholdPx = 0f

    private val mainHandler = Handler(Looper.getMainLooper())
    private var progressAnimator: ValueAnimator? = null

    private val holdCompletedRunnable = Runnable {
        if (isHolding && isGatewayShowing) {
            isHoldCompleted = true
            holdWindowExpiryTime = System.currentTimeMillis() + 3500L
            vibrateFeedback()

            binding.progressGestureHold.progress = 100
            binding.tvGestureFeedback.text = "Hold verified! Swipe right to open ->"
            binding.tvGestureFeedback.setTextColor(0xFF2563EB.toInt())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val density = resources.displayMetrics.density
        touchSlopPx = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        swipeThresholdPx = 80f * density

        setupFirebase()
        setupGatewayScreen()
        setupDashboard()
    }

    private fun setupFirebase() {
        try {
            // Check if default FirebaseApp is already initialized
            val hasApps = FirebaseApp.getApps(this).isNotEmpty()
            if (!hasApps) {
                // Initialize default FirebaseApp using project metadata
                val options = FirebaseOptions.Builder()
                    .setApplicationId(packageName)
                    .setProjectId(PROJECT_ID)
                    .setDatabaseUrl(DATABASE_URL)
                    .setApiKey("AIzaSyB" + "PlaceholderKeyForAppClientInitialization00")
                    .build()
                FirebaseApp.initializeApp(this, options)
                Log.d(TAG, "FirebaseApp initialized programmatically")
            }

            auth = FirebaseAuth.getInstance()
            database = FirebaseDatabase.getInstance(DATABASE_URL)
            channelRef = database?.getReference("channel_data")
            connectedRef = database?.getReference(".info/connected")
            binding.tvDatabaseUrl.text = DATABASE_URL
        } catch (e: Exception) {
            Log.e(TAG, "Firebase initialization error: ${e.message}", e)
            binding.tvDatabaseUrl.text = "Firebase: Local / Demo Mode"
            binding.tvConnectionStatus.text = "LOCAL MODE"
            binding.tvConnectionStatus.setBackgroundResource(R.drawable.bg_badge_warning)
            binding.tvConnectionStatus.setTextColor(0xFF92400E.toInt())
        }
    }

    private fun setupGatewayScreen() {
        binding.gatewayLayout.visibility = View.VISIBLE
        binding.dashboardLayout.visibility = View.GONE
        isGatewayShowing = true

        // Sign In
        binding.btnAuthSignIn.setOnClickListener {
            val email = binding.etAuthEmail.text.toString().trim()
            val pass = binding.etAuthPassword.text.toString().trim()

            if (email.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, "Please enter email and password", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val currentAuth = auth
            if (currentAuth == null) {
                Toast.makeText(this, "Firebase Auth offline. Proceeding in offline mode.", Toast.LENGTH_SHORT).show()
                unlockDashboard(email)
                return@setOnClickListener
            }

            binding.tvGestureFeedback.text = "Authenticating with Firebase..."
            currentAuth.signInWithEmailAndPassword(email, pass)
                .addOnSuccessListener {
                    binding.tvGestureFeedback.text = ""
                    unlockDashboard(email)
                }
                .addOnFailureListener { e ->
                    binding.tvGestureFeedback.text = "Sign-in error: ${e.localizedMessage}"
                    Toast.makeText(this, "Authentication: ${e.message}", Toast.LENGTH_LONG).show()
                }
        }

        // Sign Up
        binding.btnAuthSignUp.setOnClickListener {
            val email = binding.etAuthEmail.text.toString().trim()
            val pass = binding.etAuthPassword.text.toString().trim()

            if (email.isEmpty() || pass.length < 6) {
                Toast.makeText(this, "Please enter email and password (min 6 characters)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val currentAuth = auth
            if (currentAuth == null) {
                Toast.makeText(this, "Firebase Auth offline. Proceeding in offline mode.", Toast.LENGTH_SHORT).show()
                unlockDashboard(email)
                return@setOnClickListener
            }

            binding.tvGestureFeedback.text = "Creating user account..."
            currentAuth.createUserWithEmailAndPassword(email, pass)
                .addOnSuccessListener {
                    binding.tvGestureFeedback.text = ""
                    Toast.makeText(this, "Account created successfully!", Toast.LENGTH_SHORT).show()
                    unlockDashboard(email)
                }
                .addOnFailureListener { e ->
                    binding.tvGestureFeedback.text = "Registration error: ${e.localizedMessage}"
                    Toast.makeText(this, "Registration: ${e.message}", Toast.LENGTH_LONG).show()
                }
        }

        // Forgot Password
        binding.tvForgotPassword.setOnClickListener {
            showForgotPasswordDialog()
        }

        // Quick Unlock accessibility button
        binding.btnQuickUnlock.setOnClickListener {
            val user = auth?.currentUser
            unlockDashboard(user?.email ?: "Quick Session ($PROJECT_ID)")
        }
    }

    private fun showForgotPasswordDialog() {
        val input = EditText(this).apply {
            hint = "Registered Email address"
            setText(binding.etAuthEmail.text.toString().trim())
            setPadding(40, 30, 40, 30)
        }

        AlertDialog.Builder(this)
            .setTitle("Reset Password")
            .setMessage("We will send a password reset link to your email address.")
            .setView(input)
            .setPositiveButton("Send Reset Link") { _: DialogInterface, _: Int ->
                val email = input.text.toString().trim()
                if (email.isNotEmpty()) {
                    val currentAuth = auth
                    if (currentAuth != null) {
                        currentAuth.sendPasswordResetEmail(email)
                            .addOnSuccessListener {
                                Toast.makeText(this, "Reset link sent to $email", Toast.LENGTH_LONG).show()
                            }
                            .addOnFailureListener { e ->
                                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                    } else {
                        Toast.makeText(this, "Password reset simulation: Link queued for $email", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "Email cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupDashboard() {
        // Lock Workspace Button
        binding.btnLockWorkspace.setOnClickListener {
            lockWorkspace()
        }

        // Send Data to RTDB
        binding.btnSendDatabase.setOnClickListener {
            val text = binding.etDatabasePayload.text.toString().trim()
            if (text.isNotEmpty()) {
                publishPayload(text)
            } else {
                Toast.makeText(this, "Please enter a message or payload", Toast.LENGTH_SHORT).show()
            }
        }

        // Preset Quick Buttons
        binding.btnPresetPing.setOnClickListener {
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            publishPayload("PING @ $timestamp")
        }

        binding.btnPresetStatus.setOnClickListener {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            publishPayload("STATUS_OK: Device synchronized at $timestamp")
        }

        binding.btnPresetClear.setOnClickListener {
            val ref = channelRef
            if (ref != null) {
                ref.removeValue()
                    .addOnSuccessListener {
                        Toast.makeText(this, "Channel data cleared", Toast.LENGTH_SHORT).show()
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(this, "Clear failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
            } else {
                binding.tvLiveDataDisplay.text = "[Channel node is currently empty]"
                Toast.makeText(this, "Channel reset locally", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun publishPayload(payload: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val dataMap = mapOf(
            "payload" to payload,
            "timestamp" to timestamp,
            "user" to (auth?.currentUser?.email ?: "session_user")
        )

        val ref = channelRef
        if (ref != null) {
            ref.setValue(dataMap)
                .addOnSuccessListener {
                    binding.etDatabasePayload.text?.clear()
                    Toast.makeText(this, "Published to Realtime Database", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Publish failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
        } else {
            binding.tvLiveDataDisplay.text = dataMap.toString()
            binding.tvLastSynced.text = "Local sync @ $timestamp"
            binding.etDatabasePayload.text?.clear()
            Toast.makeText(this, "Saved to local stream", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startDatabaseListeners() {
        val targetRef = channelRef
        if (targetRef != null) {
            channelListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists()) {
                        val rawVal = snapshot.value
                        binding.tvLiveDataDisplay.text = rawVal.toString()
                        val now = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                        binding.tvLastSynced.text = "Last updated from RTDB: $now"
                    } else {
                        binding.tvLiveDataDisplay.text = "[Channel node is currently empty]"
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    binding.tvLiveDataDisplay.text = "Channel notice: ${error.message}"
                }
            }
            targetRef.addValueEventListener(channelListener as ValueEventListener)
        }

        val conn = connectedRef
        if (conn != null) {
            connectedListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val isConnected = snapshot.getValue(Boolean::class.java) ?: false
                    if (isConnected) {
                        binding.tvConnectionStatus.text = "CONNECTED"
                        binding.tvConnectionStatus.setBackgroundResource(R.drawable.bg_badge_success)
                        binding.tvConnectionStatus.setTextColor(0xFF166534.toInt())
                    } else {
                        binding.tvConnectionStatus.text = "OFFLINE"
                        binding.tvConnectionStatus.setBackgroundResource(R.drawable.bg_badge_warning)
                        binding.tvConnectionStatus.setTextColor(0xFF92400E.toInt())
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    binding.tvConnectionStatus.text = "ERROR"
                }
            }
            conn.addValueEventListener(connectedListener as ValueEventListener)
        }
    }

    private fun stopDatabaseListeners() {
        channelListener?.let { channelRef?.removeEventListener(it) }
        connectedListener?.let { connectedRef?.removeEventListener(it) }
        channelListener = null
        connectedListener = null
    }

    private fun unlockDashboard(userLabel: String) {
        if (!isGatewayShowing) return

        isGatewayShowing = false
        resetHoldState()
        vibrateFeedback()

        binding.tvSessionUser.text = "Account: $userLabel"

        binding.gatewayLayout.animate()
            .alpha(0f)
            .setDuration(250)
            .withEndAction {
                binding.gatewayLayout.visibility = View.GONE
                binding.gatewayLayout.alpha = 1f

                binding.dashboardLayout.visibility = View.VISIBLE
                binding.dashboardLayout.alpha = 0f
                binding.dashboardLayout.animate()
                    .alpha(1f)
                    .setDuration(250)
                    .start()

                startDatabaseListeners()
            }
            .start()

        Toast.makeText(this, "Workspace Unlocked", Toast.LENGTH_SHORT).show()
    }

    private fun lockWorkspace() {
        isGatewayShowing = true
        resetHoldState()
        stopDatabaseListeners()

        binding.dashboardLayout.visibility = View.GONE
        binding.gatewayLayout.alpha = 1f
        binding.gatewayLayout.visibility = View.VISIBLE

        Toast.makeText(this, "Workspace Locked", Toast.LENGTH_SHORT).show()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!isGatewayShowing) {
            return super.dispatchTouchEvent(ev)
        }

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startTouchX = ev.x
                startTouchY = ev.y
                isHolding = true

                if (System.currentTimeMillis() > holdWindowExpiryTime) {
                    isHoldCompleted = false
                }

                if (!isHoldCompleted) {
                    startHoldTimer()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - startTouchX
                val dy = ev.y - startTouchY
                val distanceMoved = hypot(dx.toDouble(), dy.toDouble()).toFloat()

                if (isHolding && !isHoldCompleted) {
                    if (distanceMoved > touchSlopPx) {
                        cancelHoldTimer()
                    }
                } else if (isHoldCompleted) {
                    if (dx >= swipeThresholdPx) {
                        val user = auth?.currentUser
                        unlockDashboard(user?.email ?: "Gesture Session ($PROJECT_ID)")
                        return true
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val dx = ev.x - startTouchX

                if (isHoldCompleted && dx >= swipeThresholdPx) {
                    val user = auth?.currentUser
                    unlockDashboard(user?.email ?: "Gesture Session ($PROJECT_ID)")
                    return true
                }

                if (!isHoldCompleted) {
                    cancelHoldTimer()
                }
                isHolding = false
            }
        }

        return super.dispatchTouchEvent(ev)
    }

    private fun startHoldTimer() {
        mainHandler.removeCallbacks(holdCompletedRunnable)
        progressAnimator?.cancel()

        binding.progressGestureHold.visibility = View.VISIBLE
        binding.progressGestureHold.progress = 0
        binding.tvGestureFeedback.text = "Holding to unlock..."
        binding.tvGestureFeedback.setTextColor(0xFF64748B.toInt())

        progressAnimator = ValueAnimator.ofInt(0, 100).apply {
            duration = holdDurationMs
            addUpdateListener { animator ->
                binding.progressGestureHold.progress = animator.animatedValue as Int
            }
            start()
        }

        mainHandler.postDelayed(holdCompletedRunnable, holdDurationMs)
    }

    private fun cancelHoldTimer() {
        mainHandler.removeCallbacks(holdCompletedRunnable)
        progressAnimator?.cancel()
        progressAnimator = null

        binding.progressGestureHold.progress = 0
        binding.progressGestureHold.visibility = View.INVISIBLE
        binding.tvGestureFeedback.text = ""
        isHolding = false
    }

    private fun resetHoldState() {
        mainHandler.removeCallbacks(holdCompletedRunnable)
        progressAnimator?.cancel()
        progressAnimator = null

        isHolding = false
        isHoldCompleted = false
        holdWindowExpiryTime = 0L

        binding.progressGestureHold.progress = 0
        binding.progressGestureHold.visibility = View.INVISIBLE
        binding.tvGestureFeedback.text = ""
    }

    private fun vibrateFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(100)
                }
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDatabaseListeners()
        mainHandler.removeCallbacks(holdCompletedRunnable)
        progressAnimator?.cancel()
    }
}
