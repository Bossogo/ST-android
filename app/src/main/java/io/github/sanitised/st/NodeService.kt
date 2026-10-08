package io.github.sanitised.st

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class NodeService : Service() {
    companion object {
        const val ACTION_START = "io.github.sanitised.st.action.START_NODE"
        const val ACTION_STOP = "io.github.sanitised.st.action.STOP_NODE"
        const val EXTRA_PORT = "io.github.sanitised.st.extra.PORT"
        private const val CHANNEL_ID = "node_service_v2"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_LOG_BYTES = 10L * 1024L * 1024L
        private const val PREFS_NAME = "node_service"
        private const val PREF_WANT_RUNNING = "want_running"
        private const val PREF_PORT = "port"
        private const val WATCHDOG_INTERVAL_MS = 15_000L
        private const val RESTART_DELAY_MS = 2_000L
        private const val HEALTH_FAIL_THRESHOLD = 3
    }

    inner class LocalBinder : Binder() {
        fun getService(): NodeService = this@NodeService
    }

    private val binder = LocalBinder()
    private val listeners = CopyOnWriteArraySet<NodeStatusListener>()
    private val payload = NodePayload(this)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    @Volatile
    private var process: Process? = null
    private var status: NodeStatus = NodeStatus(NodeState.STOPPED, "")
    @Volatile
    private var stopRequested = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var watchdogJob: Job? = null
    private var restartJob: Job? = null
    private val restartScheduled = AtomicBoolean(false)
    private var consecutiveHealthFailures = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        status = status.copy(
            message = getString(R.string.node_status_idle),
            port = prefs.getInt(PREF_PORT, DEFAULT_PORT)
        )
        notifyStatus(status)
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        stopWatchdog()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val port = intent.getIntExtra(EXTRA_PORT, status.port)
                setWantRunning(true, port)
                if (ensureForeground(getString(R.string.node_status_starting))) {
                    startNodeAsync()
                }
            }
            ACTION_STOP -> {
                setWantRunning(false, status.port)
                stopNode()
            }
            null -> {
                // START_STICKY restart after process death
                if (prefs.getBoolean(PREF_WANT_RUNNING, false)) {
                    val port = prefs.getInt(PREF_PORT, status.port)
                    setPort(port)
                    if (ensureForeground(getString(R.string.node_status_starting))) {
                        startNodeAsync()
                    }
                } else {
                    stopSelf()
                }
            }
            else -> {
                if (prefs.getBoolean(PREF_WANT_RUNNING, false) && process == null) {
                    if (ensureForeground(getString(R.string.node_status_starting))) {
                        startNodeAsync()
                    }
                }
            }
        }
        return START_STICKY
    }

    fun registerListener(listener: NodeStatusListener) {
        listeners.add(listener)
        listener.onStatus(status)
    }

    fun unregisterListener(listener: NodeStatusListener) {
        listeners.remove(listener)
    }

    private fun setWantRunning(want: Boolean, port: Int) {
        val safePort = if (port in 1..65535) port else DEFAULT_PORT
        prefs.edit()
            .putBoolean(PREF_WANT_RUNNING, want)
            .putInt(PREF_PORT, safePort)
            .apply()
        setPort(safePort)
    }

    private fun startNodeAsync() {
        restartJob?.cancel()
        restartScheduled.set(false)
        val shouldStart = synchronized(this) {
            if (process != null) return@synchronized false
            if (status.state == NodeState.STARTING || status.state == NodeState.STOPPING) return@synchronized false
            stopRequested = false
            status = status.copy(
                state = NodeState.STARTING,
                message = getString(R.string.node_status_starting)
            )
            true
        }
        if (!shouldStart) return
        notifyStatus(status)
        acquireWakeLock()
        serviceScope.launch { startNodeInternal() }
    }

    private fun startNodeInternal() {
        synchronized(this) {
            if (process != null) {
                return
            }
        }
        val layout = try {
            val layoutResult = payload.ensureExtracted()
            if (layoutResult.isFailure) {
                updateStatus(
                    NodeState.ERROR,
                    layoutResult.exceptionOrNull()?.message ?: getString(R.string.node_status_extraction_failed)
                )
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                return
            }
            layoutResult.getOrThrow()
        } catch (e: Exception) {
            updateStatus(NodeState.ERROR, e.message ?: getString(R.string.node_status_extraction_failed))
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }
        if (!layout.nodeBin.exists()) {
            updateStatus(NodeState.ERROR, getString(R.string.node_status_binary_not_found))
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }
        if (stopRequested) {
            updateStatus(NodeState.STOPPED, getString(R.string.node_status_stopped))
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }

        try {
            if (!layout.logsDir.exists()) {
                layout.logsDir.mkdirs()
            }
            if (layout.payloadUpdated) {
                appendServiceLog(layout.logsDir, "post-install: starting")
                runPostInstall(layout)
                appendServiceLog(layout.logsDir, "post-install: complete")
            }
            val stdout = File(layout.logsDir, "node_stdout.log")
            val stderr = File(layout.logsDir, "node_stderr.log")
            rotateLogIfNeeded(stdout, MAX_LOG_BYTES)
            rotateLogIfNeeded(stderr, MAX_LOG_BYTES)
            val builder = ProcessBuilder(
                layout.nodeBin.absolutePath,
                layout.appEntry.absolutePath,
                "--configPath",
                layout.configFile.absolutePath,
                "--dataRoot",
                layout.dataDir.absolutePath,
                "--browserLaunchEnabled",
                "false"
            )
            builder.directory(layout.appDir)
            val tmpDir = AppPaths(this).nodeTmpDir
            tmpDir.mkdirs()
            builder.environment()["PORT"] = status.port.toString()
            builder.environment()["HOME"] = filesDir.absolutePath
            builder.environment()["TMPDIR"] = tmpDir.absolutePath
            builder.environment()["TMP"] = tmpDir.absolutePath
            builder.environment()["TEMP"] = tmpDir.absolutePath
            builder.environment()["LD_LIBRARY_PATH"] = applicationInfo.nativeLibraryDir
            builder.environment()["NODE_ENV"] = "production"
            builder.environment()["ST_ANDROID"] = "1"
            builder.redirectOutput(stdout)
            builder.redirectError(stderr)
            val startedProcess = builder.start()
            process = startedProcess

            updateStatus(NodeState.RUNNING, getString(R.string.node_status_running))
            consecutiveHealthFailures = 0
            startWatchdog()
            waitForExitAsync(startedProcess)
        } catch (e: Exception) {
            appendServiceLog(layout.logsDir, "start failed: ${e.message ?: "unknown error"}")
            updateStatus(NodeState.ERROR, e.message ?: getString(R.string.node_status_start_failed))
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            maybeScheduleRestart("start-failed")
        }
    }

    private fun runPostInstall(layout: NodePayload.Layout) {
        val script = File(layout.appDir, "post-install.js")
        if (!script.exists()) {
            appendServiceLog(layout.logsDir, "post-install: script not found")
            return
        }
        val stdout = File(layout.logsDir, "post_install_stdout.log")
        val stderr = File(layout.logsDir, "post_install_stderr.log")
        val builder = ProcessBuilder(layout.nodeBin.absolutePath, script.absolutePath)
        builder.directory(layout.appDir)
        val tmpDir = AppPaths(this).nodeTmpDir
        tmpDir.mkdirs()
        builder.environment()["HOME"] = filesDir.absolutePath
        builder.environment()["TMPDIR"] = tmpDir.absolutePath
        builder.environment()["TMP"] = tmpDir.absolutePath
        builder.environment()["TEMP"] = tmpDir.absolutePath
        builder.environment()["LD_LIBRARY_PATH"] = applicationInfo.nativeLibraryDir
        builder.environment()["NODE_ENV"] = "production"
        builder.environment()["ST_ANDROID"] = "1"
        builder.redirectOutput(stdout)
        builder.redirectError(stderr)
        val proc = builder.start()
        if (!proc.waitFor(30, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            throw IllegalStateException("post-install.js timed out")
        }
        val exitCode = proc.exitValue()
        if (exitCode != 0) {
            throw IllegalStateException("post-install.js failed with code $exitCode")
        }
    }

    private fun appendServiceLog(logsDir: File, message: String) {
        if (!logsDir.exists()) {
            logsDir.mkdirs()
        }
        val logFile = File(logsDir, "service.log")
        rotateLogIfNeeded(logFile, MAX_LOG_BYTES)
        val line = formatServiceLogLine(message)
        logFile.appendText(line, Charsets.UTF_8)
    }

    fun stopNode() {
        serviceScope.launch { stopNodeInternal() }
    }

    private fun stopNodeInternal() {
        stopWatchdog()
        restartJob?.cancel()
        restartScheduled.set(false)
        val proc = synchronized(this) {
            if (status.state == NodeState.STOPPED || status.state == NodeState.STOPPING) {
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            stopRequested = true
            val current = process
            updateStatus(NodeState.STOPPING, getString(R.string.node_status_stopping))
            current
        }

        if (proc == null) {
            updateStatus(NodeState.STOPPED, getString(R.string.node_status_stopped))
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        try {
            proc.destroy()
            if (!proc.waitFor(3, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
            }
        } catch (_: Exception) {
            proc.destroyForcibly()
        } finally {
            synchronized(this) {
                if (process === proc) {
                    process = null
                }
            }
            updateStatus(NodeState.STOPPED, getString(R.string.node_status_stopped))
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun waitForExitAsync(startedProcess: Process) {
        serviceScope.launch {
            val exitCode = try {
                startedProcess.waitFor()
            } catch (_: Exception) {
                null
            }
            val wasStopRequested = stopRequested || status.state == NodeState.STOPPING
            synchronized(this@NodeService) {
                if (process === startedProcess) {
                    process = null
                }
            }
            stopWatchdog()
            if (wasStopRequested) {
                updateStatus(NodeState.STOPPED, getString(R.string.node_status_stopped))
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else {
                if (exitCode == 0) {
                    updateStatus(NodeState.STOPPED, getString(R.string.node_status_exited))
                } else {
                    val message = getString(R.string.node_status_exited_with_code, exitCode?.toString() ?: "?")
                    updateStatus(NodeState.ERROR, message)
                }
                maybeScheduleRestart("process-exit")
            }
        }
    }

    private fun maybeScheduleRestart(reason: String) {
        if (!prefs.getBoolean(PREF_WANT_RUNNING, false) || stopRequested) {
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        if (!restartScheduled.compareAndSet(false, true)) return
        appendServiceLog(AppPaths(this).logsDir, "watchdog: scheduling restart ($reason)")
        updateStatus(NodeState.STARTING, getString(R.string.notification_watchdog_restart))
        ensureForeground(getString(R.string.notification_watchdog_restart))
        restartJob = serviceScope.launch {
            delay(RESTART_DELAY_MS)
            restartScheduled.set(false)
            if (prefs.getBoolean(PREF_WANT_RUNNING, false) && !stopRequested) {
                startNodeAsync()
            }
        }
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = serviceScope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                if (stopRequested || !prefs.getBoolean(PREF_WANT_RUNNING, false)) continue
                val proc = process
                if (proc == null || !proc.isAlive) {
                    appendServiceLog(AppPaths(this@NodeService).logsDir, "watchdog: process missing")
                    maybeScheduleRestart("watchdog-missing-process")
                    continue
                }
                val healthy = probeLocalServer(status.port)
                if (healthy) {
                    consecutiveHealthFailures = 0
                    acquireWakeLock()
                } else {
                    consecutiveHealthFailures += 1
                    appendServiceLog(
                        AppPaths(this@NodeService).logsDir,
                        "watchdog: health check failed ($consecutiveHealthFailures/$HEALTH_FAIL_THRESHOLD)"
                    )
                    if (consecutiveHealthFailures >= HEALTH_FAIL_THRESHOLD) {
                        consecutiveHealthFailures = 0
                        runCatching { proc.destroy() }
                        maybeScheduleRestart("watchdog-unhealthy")
                    }
                }
            }
        }
    }

    private fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
        consecutiveHealthFailures = 0
    }

    private fun probeLocalServer(port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 800)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        val existing = wakeLock
        if (existing?.isHeld == true) {
            existing.acquire(10 * 60 * 1000L)
            return
        }
        val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TavernPocket:NodeService").apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 1000L)
        }
        wakeLock = lock
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        } finally {
            wakeLock = null
        }
    }

    private fun rotateLogIfNeeded(file: File, maxBytes: Long) {
        if (!file.exists()) return
        if (file.length() <= maxBytes) return
        val backup = File(file.parentFile, "${file.name}.1")
        if (backup.exists()) {
            backup.delete()
        }
        file.renameTo(backup)
        file.writeText("", Charsets.UTF_8)
    }

    fun runPostInstallNow(): Result<Unit> {
        synchronized(this) {
            if (process != null) {
                return Result.failure(IllegalStateException("Node is running"))
            }
        }
        return runCatching {
            val layoutResult = payload.ensureExtracted()
            if (layoutResult.isFailure) {
                throw layoutResult.exceptionOrNull() ?: IllegalStateException("Extraction failed")
            }
            val layout = layoutResult.getOrThrow()
            if (!layout.logsDir.exists()) {
                layout.logsDir.mkdirs()
            }
            appendServiceLog(layout.logsDir, "post-install: starting (manual)")
            runPostInstall(layout)
            appendServiceLog(layout.logsDir, "post-install: complete (manual)")
        }
    }

    private fun updateStatus(state: NodeState, message: String, pid: Long? = status.pid) {
        status = status.copy(state = state, message = message, pid = pid)
        notifyStatus(status)
    }

    private fun setPort(port: Int) {
        val safePort = if (port in 1..65535) port else DEFAULT_PORT
        status = status.copy(port = safePort)
        notifyStatus(status)
    }

    private fun notifyStatus(newStatus: NodeStatus) {
        for (listener in listeners) {
            listener.onStatus(newStatus)
        }
        val manager = NotificationManagerCompat.from(this)
        if (newStatus.state == NodeState.STOPPED && !prefs.getBoolean(PREF_WANT_RUNNING, false)) {
            manager.cancel(NOTIFICATION_ID)
        } else {
            manager.notify(
                NOTIFICATION_ID,
                buildNotification("${newStatus.state}: ${newStatus.message}")
            )
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, NodeService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setContentIntent(openPending)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(status.state == NodeState.RUNNING || status.state == NodeState.STARTING)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.notification_stop_action), stopPending)
            .build()
    }

    private fun ensureForeground(message: String): Boolean {
        return try {
            startForeground(NOTIFICATION_ID, buildNotification(message))
            true
        } catch (e: Exception) {
            updateStatus(NodeState.ERROR, e.message ?: getString(R.string.node_status_foreground_not_allowed))
            stopForeground(STOP_FOREGROUND_REMOVE)
            false
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.enableVibration(false)
        channel.setSound(null, null)
        channel.setShowBadge(false)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
