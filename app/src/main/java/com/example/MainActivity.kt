package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.ExecutionScreen
import com.example.ui.SetupScreen
import com.example.ui.TakeoutViewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val viewModel: TakeoutViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                // Request POST_NOTIFICATIONS permission on Android 13+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val permissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestPermission()
                    ) { _ -> }

                    LaunchedEffect(Unit) {
                        val hasPermission = ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!hasPermission) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }

                val jobState by viewModel.jobState.collectAsStateWithLifecycle()
                val selectedZips by viewModel.selectedZips.collectAsStateWithLifecycle()
                val destinationName by viewModel.destinationName.collectAsStateWithLifecycle()
                val recreateFolders by viewModel.recreateFolders.collectAsStateWithLifecycle()
                val timezoneOption by viewModel.timezoneOption.collectAsStateWithLifecycle()
                val freeSpaceWarning by viewModel.freeSpaceWarning.collectAsStateWithLifecycle()
                val logs by viewModel.logs.collectAsStateWithLifecycle()

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val isJobActive = jobState != null && jobState?.phase != "IDLE"

                    if (isJobActive && jobState != null) {
                        ExecutionScreen(
                            jobState = jobState!!,
                            logs = logs,
                            onCancel = { viewModel.cancelJob() },
                            onReset = { viewModel.resetJob() },
                            onExportLogs = { uri, callback ->
                                viewModel.exportLogsToUri(uri, callback)
                            }
                        )
                    } else {
                        SetupScreen(
                            selectedZips = selectedZips,
                            destinationName = destinationName,
                            recreateFolders = recreateFolders,
                            timezoneOption = timezoneOption,
                            freeSpaceWarning = freeSpaceWarning,
                            onAddZips = { uris -> viewModel.addZipUris(uris) },
                            onRemoveZip = { uri -> viewModel.removeZip(uri) },
                            onSelectDestination = { uri -> viewModel.setDestination(uri) },
                            onToggleRecreateFolders = { value -> viewModel.setRecreateFolders(value) },
                            onSelectTimezone = { option -> viewModel.setTimezoneOption(option) },
                            onStartJob = { viewModel.startJob() }
                        )
                    }
                }
            }
        }
    }
}
