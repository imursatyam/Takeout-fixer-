package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.data.AppDatabase
import com.example.data.AppRepository
import com.example.metadata.MediaMetadataApplier
import com.example.model.JobState
import com.example.model.LogRecord
import com.example.worker.TakeoutRestoreWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.time.ZoneId
import java.util.Locale

data class SelectedZip(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long
)

class TakeoutViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AppRepository(AppDatabase.getInstance(application))
    private val workManager = WorkManager.getInstance(application)

    private val _selectedZips = MutableStateFlow<List<SelectedZip>>(emptyList())
    val selectedZips: StateFlow<List<SelectedZip>> = _selectedZips.asStateFlow()

    private val _destinationUri = MutableStateFlow<Uri?>(null)
    val destinationUri: StateFlow<Uri?> = _destinationUri.asStateFlow()

    private val _destinationName = MutableStateFlow<String?>(null)
    val destinationName: StateFlow<String?> = _destinationName.asStateFlow()

    private val _recreateFolders = MutableStateFlow(true)
    val recreateFolders: StateFlow<Boolean> = _recreateFolders.asStateFlow()

    private val _timezoneOption = MutableStateFlow("DEVICE") // "DEVICE" or "UTC"
    val timezoneOption: StateFlow<String> = _timezoneOption.asStateFlow()

    private val _freeSpaceWarning = MutableStateFlow<String?>(null)
    val freeSpaceWarning: StateFlow<String?> = _freeSpaceWarning.asStateFlow()

    val jobState: StateFlow<JobState?> = repository.jobStateFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val logs: StateFlow<List<LogRecord>> = repository.getRecentLogs(250)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    companion object {
        const val UNIQUE_WORK_NAME = "TakeoutRestorerWork"
    }

    fun addZipUris(uris: List<Uri>) {
        val context = getApplication<Application>()
        val current = _selectedZips.value.toMutableList()

        for (uri in uris) {
            if (current.none { it.uri == uri }) {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {}

                var name = uri.lastPathSegment ?: "takeout.zip"
                var size = 0L

                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIndex != -1) name = cursor.getString(nameIndex)
                        if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                    }
                }

                current.add(SelectedZip(uri, name, size))
            }
        }
        _selectedZips.value = current
        checkFreeSpace()
    }

    fun removeZip(uri: Uri) {
        _selectedZips.value = _selectedZips.value.filter { it.uri != uri }
        checkFreeSpace()
    }

    fun setDestination(treeUri: Uri) {
        val context = getApplication<Application>()
        try {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(treeUri, takeFlags)
        } catch (_: Exception) {}

        _destinationUri.value = treeUri

        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val name = docId.substringAfterLast(':').ifEmpty { "Selected Folder" }
        _destinationName.value = name

        checkFreeSpace()
    }

    fun setRecreateFolders(recreate: Boolean) {
        _recreateFolders.value = recreate
    }

    fun setTimezoneOption(option: String) {
        _timezoneOption.value = option
    }

    private fun checkFreeSpace() {
        val dest = _destinationUri.value ?: run {
            _freeSpaceWarning.value = null
            return
        }

        val totalZipBytes = _selectedZips.value.sumOf { it.sizeBytes }
        if (totalZipBytes <= 0) {
            _freeSpaceWarning.value = null
            return
        }

        val realPath = MediaMetadataApplier.resolveRealPath(dest)
        if (realPath != null) {
            try {
                val stat = StatFs(realPath)
                val availableBytes = stat.availableBytes
                if (availableBytes < totalZipBytes) {
                    val availGb = availableBytes / (1024.0 * 1024.0 * 1024.0)
                    val neededGb = totalZipBytes / (1024.0 * 1024.0 * 1024.0)
                    _freeSpaceWarning.value =
                        "Low storage: Destination has ~%.1f GB free, but selected archives total ~%.1f GB. Free up space to prevent disk full errors."
                            .format(Locale.US, availGb, neededGb)
                    return
                }
            } catch (_: Exception) {}
        }
        _freeSpaceWarning.value = null
    }

    fun startJob() {
        val zips = _selectedZips.value
        val dest = _destinationUri.value ?: return
        if (zips.isEmpty()) return

        val zoneIdStr = if (_timezoneOption.value == "UTC") "UTC" else ZoneId.systemDefault().id

        viewModelScope.launch {
            repository.clearJobData()

            val inputData = Data.Builder()
                .putStringArray(TakeoutRestoreWorker.KEY_ZIP_URIS, zips.map { it.uri.toString() }.toTypedArray())
                .putString(TakeoutRestoreWorker.KEY_DEST_URI, dest.toString())
                .putBoolean(TakeoutRestoreWorker.KEY_RECREATE_FOLDERS, _recreateFolders.value)
                .putString(TakeoutRestoreWorker.KEY_TIMEZONE, zoneIdStr)
                .build()

            val request = OneTimeWorkRequestBuilder<TakeoutRestoreWorker>()
                .setInputData(inputData)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            workManager.enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }

    fun cancelJob() {
        workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
        viewModelScope.launch {
            val state = repository.getJobState()
            if (state != null) {
                repository.updateJobState(state.copy(phase = "STOPPED"))
            }
            repository.insertLog("WARN", "Restoration cancelled by user.")
        }
    }

    fun resetJob() {
        cancelJob()
        viewModelScope.launch {
            repository.clearJobData()
        }
    }

    fun exportLogsToUri(destUri: Uri, onResult: (Boolean) -> Unit) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            try {
                val allLogs = repository.getAllLogs()
                context.contentResolver.openOutputStream(destUri)?.use { outStream ->
                    outStream.bufferedWriter().use { writer ->
                        writer.write("Takeout Restorer - Execution Log\n")
                        writer.write("Generated at: ${System.currentTimeMillis()}\n")
                        writer.write("====================================================\n\n")
                        for (log in allLogs) {
                            writer.write("[${log.level}] ${log.message}\n")
                        }
                    }
                }
                onResult(true)
            } catch (e: Exception) {
                onResult(false)
            }
        }
    }
}
