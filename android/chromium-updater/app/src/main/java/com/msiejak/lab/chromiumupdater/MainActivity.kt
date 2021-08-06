package com.msiejak.lab.chromiumupdater

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.DownloadManager
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.msiejak.lab.chromiumupdater.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import android.net.ConnectivityManager
import com.android.volley.Request
import com.android.volley.RequestQueue
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import org.json.JSONException


class MainActivity : AppCompatActivity() {
    private lateinit var receiver: BroadcastReceiver
    private lateinit var receiver2: BroadcastReceiver
    private lateinit var binding: ActivityMainBinding
    private var downloaded = false
    private var currentRemote: Long = 0
    private var chromiumInstalled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val alarmManager =
            getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val pendingIntent =
            PendingIntent.getService(this, 1, intent, PendingIntent.FLAG_IMMUTABLE)
        if (pendingIntent != null && alarmManager != null) {
            alarmManager.cancel(pendingIntent)
        }
        setChromiumVersionText()
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                unzip(File(externalCacheDir, "/chromium/chromium.zip"), File(externalCacheDir, "/chromium/extracted"))
            }
        }
        registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        if(chromiumInstalled) {
            binding.startButton.setOnClickListener{ checkForUpdate() }
        }else {
            binding.startButton.setOnClickListener{ downloadBuild() }
        }
        binding.topAppBar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.refresh -> {
                    setChromiumVersionText()
                    checkForUpdate()
                    true
                }else -> true
            }
        }
    }

    private fun downloadBuild() {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        if(connectivityManager.activeNetworkInfo != null && connectivityManager.activeNetworkInfo!!.isConnected) {

            binding.progressIndicator.visibility = View.VISIBLE
            binding.startButton.isEnabled = false
            Toast.makeText(this, "working...", Toast.LENGTH_LONG).show()
            File(externalCacheDir?.absolutePath + "/chromium").deleteRecursively()
            File(externalCacheDir?.absolutePath + "/chromium").mkdir()
            val request =
                DownloadManager.Request(Uri.parse("https://download-chromium.appspot.com/dl/Android?type=snapshots"))
            val uri = "file://${externalCacheDir?.absolutePath}/chromium/chromium.zip".toUri()
            request.setDestinationUri(uri)
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
        } else {
            val builder = MaterialAlertDialogBuilder(this)
            builder.setTitle("Network Error")
            builder.setCancelable(false)
            builder.setIcon(R.drawable.ic_baseline_error_outline_24)
            builder.setPositiveButton("retry"
            ) { _: DialogInterface?, _: Int ->
                downloadBuild()
            }
            builder.setMessage("You will be unable to download Chromium until you connect to the internet")
            builder.show()
        }
    }



    @Throws(IOException::class)
    private fun unzip(zipFile: File?, targetDirectory: File?) {
        val zis = ZipInputStream(
            BufferedInputStream(FileInputStream(zipFile))
        )
        lifecycleScope.launch(Dispatchers.IO) {
        try {
            var ze: ZipEntry
            var count: Int
            val buffer = ByteArray(8192)
            try {
                while (zis.nextEntry.also { ze = it } != null) {
                    val file = File(targetDirectory, ze.name)
                    val dir = if (ze.isDirectory) file else file.parentFile
                    if (!dir.isDirectory && !dir.mkdirs()) throw FileNotFoundException(
                        "Failed to ensure directory: " +
                                dir.absolutePath
                    )
                    if (ze.isDirectory) continue
                    val fout = FileOutputStream(file)
                    try {
                        while (zis.read(buffer).also { count = it } != -1) fout.write(
                            buffer,
                            0,
                            count
                        )
                    } finally {
                        fout.close()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } finally {
            zis.close()
            runOnUiThread {
                binding.progressIndicator.visibility = View.GONE
                binding.startButton.isEnabled = true
                install() }
            downloaded = true

        }
    }
    }

    private fun checkForUpdate() {
        val queue = Volley.newRequestQueue(this)
        val s = getSharedPreferences("shared_prefs", MODE_PRIVATE)
        val currentInstalled = s.getLong("build", 0)
        val url = "https://download-chromium.appspot.com/rev/Android?type=snapshots"
        val jsonObjectRequest = JsonObjectRequest(Request.Method.GET, url, null, { response ->
            try {
                currentRemote = response.getString("content").toLong()
                Log.e(currentRemote.toString(), "checkForUpdate: remote" )
                Log.e(currentInstalled.toString(), "checkForUpdate: installed" )
                if(currentRemote > currentInstalled) {
                    binding.startButton.setOnClickListener{ downloadBuild() }
                    binding.startButton.setText(R.string.action_update)
                    binding.updateAvaliable.text = "Update Available\nNewest Version available was built at ${response.getString("last-modified")}"
                }else {
                    binding.updateAvaliable.text = "No Update Available"
                }
                binding.progressIndicator.visibility = View.INVISIBLE
            } catch (e: JSONException) {
                Toast.makeText(this, "Error retrieving remote version", Toast.LENGTH_LONG).show()
                binding.progressIndicator.visibility = View.INVISIBLE
            }
        }) { Toast.makeText(this, "Error retrieving remote version (network error)", Toast.LENGTH_LONG).show()
            binding.progressIndicator.visibility = View.INVISIBLE}
        jsonObjectRequest.setShouldCache(false)
        jsonObjectRequest.setShouldRetryConnectionErrors(true)
        queue.add(jsonObjectRequest)
        queue.start()
        binding.progressIndicator.visibility = View.VISIBLE

    }

    private fun install() {
        receiver2 = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {

                Log.e("updated", "onReceive: ", )
            }
        }
        registerReceiver(receiver2, IntentFilter(Intent.ACTION_PACKAGE_REPLACED))
        val uri = FileProvider.getUriForFile(
            this,
            applicationContext.packageName + ".provider",
            File(externalCacheDir, "/chromium/extracted/chrome-android/apks/ChromePublic.apk")
        )
        val install = Intent(Intent.ACTION_VIEW)
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        install.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        install.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
        install.data = uri
        startActivity(install)
        getSharedPreferences("shared_prefs", MODE_PRIVATE).edit().putLong("build", currentRemote).apply()
        setChromiumVersionText()
        reset()
    }

    private fun reset() {
        binding.startButton.setOnClickListener{ checkForUpdate() }
        binding.updateAvaliable.text = ""
    }

    private fun setChromiumVersionText() {
        var txt = getString(R.string.not_installed)
        try {
            val packageInfo = packageManager.getPackageInfo("org.chromium.chrome", 0)
            txt = "Installed Chromium Version: ${packageInfo.versionName}"
            binding.startButton.setText(R.string.check_update)
            chromiumInstalled = true
        }catch(e: Exception){
            e.printStackTrace()
            binding.startButton.setText(R.string.action_install)
        }
        binding.versionName.text = txt
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
        unregisterReceiver(receiver2)
    }
}