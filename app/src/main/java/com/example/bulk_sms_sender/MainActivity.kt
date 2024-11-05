package com.example.bulk_sms_sender

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class MainActivity : ComponentActivity() {

    private lateinit var phoneNumberEditText: EditText
    private lateinit var messageEditText: EditText
    private lateinit var smsCountEditText: EditText
    private lateinit var sendButton: Button
    private lateinit var cancelButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var simSelector: Spinner

    private val requestPermissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val readPhoneStateGranted = permissions[Manifest.permission.READ_PHONE_STATE] ?: false
        val sendSmsGranted = permissions[Manifest.permission.SEND_SMS] ?: false

        if (readPhoneStateGranted && sendSmsGranted) {
            Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show()
            setupSimSelector()
        } else {
            Toast.makeText(this, "Permissions denied", Toast.LENGTH_SHORT).show()
        }
    }

    private var smsJob: Job? = null

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        phoneNumberEditText = findViewById(R.id.phoneNumber)
        messageEditText = findViewById(R.id.message)
        smsCountEditText = findViewById(R.id.smsCount)
        sendButton = findViewById(R.id.sendButton)
        cancelButton = findViewById(R.id.cancelButton)
        progressBar = findViewById(R.id.progressBar)
        simSelector = findViewById(R.id.simSelector)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
                setupSimSelector()
            } else {
                requestPermissionsLauncher.launch(arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.SEND_SMS))
            }
        } else {
            Toast.makeText(this, "Dual SIM support requires API level 22 or higher", Toast.LENGTH_SHORT).show()
            sendButton.isEnabled = false
        }

        sendButton.setOnClickListener {
            val phoneNumber = phoneNumberEditText.text.toString()
            val message = messageEditText.text.toString()
            val smsCount = smsCountEditText.text.toString().toIntOrNull()

            if (phoneNumber != "9900") {
                Toast.makeText(this, "SMS are allowed only to send 9900", Toast.LENGTH_SHORT).show()
            } else if (smsCount == null || smsCount <= 0 || smsCount > 50) {
                Toast.makeText(this, "Please enter a valid SMS count (1-50)", Toast.LENGTH_SHORT).show()
            } else {
                smsJob = sendBulkSMS(phoneNumber, message, smsCount)
            }
        }

        cancelButton.setOnClickListener {
            smsJob?.cancel()
            progressBar.visibility = ProgressBar.GONE
            Toast.makeText(this, "SMS sending cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP_MR1)
    private fun setupSimSelector() {
        val subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        val subscriptionInfoList = subscriptionManager.activeSubscriptionInfoList

        if (subscriptionInfoList != null && subscriptionInfoList.isNotEmpty()) {
            val simLabels = subscriptionInfoList.map { it.displayName.toString() }
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, simLabels)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            simSelector.adapter = adapter
        } else {
            Toast.makeText(this, "No SIM cards available", Toast.LENGTH_SHORT).show()
            sendButton.isEnabled = false
        }
    }

    private fun sendBulkSMS(phoneNumber: String, message: String, count: Int): Job {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionsLauncher.launch(arrayOf(Manifest.permission.SEND_SMS))
            return Job().apply { cancel() }
        }

        progressBar.visibility = ProgressBar.VISIBLE
        progressBar.max = 50
        progressBar.progress = 0

        val selectedSimIndex = simSelector.selectedItemPosition
        val subscriptionId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            val subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val subscriptionInfoList = subscriptionManager.activeSubscriptionInfoList
            if (subscriptionInfoList != null && subscriptionInfoList.isNotEmpty()) {
                subscriptionInfoList[selectedSimIndex].subscriptionId
            } else {
                -1 // Default value if no SIM cards are available
            }
        } else {
            -1 // Default value if API level is below 22
        }

        return CoroutineScope(Dispatchers.IO).launch {
            val smsManager = if (subscriptionId != -1) {
                SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            } else {
                SmsManager.getDefault()
            }
            for (i in 1..count) {
                if (!isActive) break
                try {
                    smsManager.sendTextMessage(phoneNumber, null, message, null, null)
                    delay(1000) // Increase delay to avoid triggering the limit
                    withContext(Dispatchers.Main) {
                        progressBar.progress = i
                        progressBar.secondaryProgress = count
                    }
                } catch (e: SecurityException) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Permission denied: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                    e.printStackTrace()
                    break // Stop the loop if an error occurs
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "SMS failed to send: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                    e.printStackTrace()
                    break // Stop the loop if an error occurs
                }
            }
            withContext(Dispatchers.Main) {
                if (progressBar.progress == count) {
                    Toast.makeText(this@MainActivity, "Sent $count SMS successfully", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Failed to send all SMS", Toast.LENGTH_SHORT).show()
                }
                progressBar.visibility = ProgressBar.GONE
            }
        }
    }
}




