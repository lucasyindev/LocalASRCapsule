package com.lucas.asrcapsule

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.lucas.asrcapsule.databinding.ActivityMainBinding
import com.lucas.asrcapsule.model.ModelManager
import com.lucas.asrcapsule.service.VoiceAccessibilityService
import com.lucas.asrcapsule.util.PreferenceHelper
import com.lucas.asrcapsule.util.VibrationHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Caret 极简主界面
 * 仅保留核心 3 项：运行状态、语音模型（切换/在线下载/导入）、震动反馈
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PreferenceHelper

    private var downloadJob: Job? = null
    private var downloadingVariant: String? = null

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        renderAll()
        if (isGranted) {
            Toast.makeText(this, "麦克风权限已开启", Toast.LENGTH_SHORT).show()
        }
    }

    private val importModelLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            copyImportedFiles(uris)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PreferenceHelper(this)

        bindInteractions()
        renderAll()
    }

    override fun onResume() {
        super.onResume()
        renderAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadJob?.cancel()
    }

    private fun bindInteractions() {
        binding.btnStatusAction.setOnClickListener {
            openMissingPermissionFlow()
        }

        binding.cardStatus.setOnClickListener {
            if (!isEnvironmentReady()) {
                openMissingPermissionFlow()
            }
        }

        binding.rowModelInt8.setOnClickListener {
            handleModelRowClick(PreferenceHelper.MODEL_VARIANT_INT8)
        }

        binding.rowModelFp16.setOnClickListener {
            handleModelRowClick(PreferenceHelper.MODEL_VARIANT_FP16)
        }

        binding.btnCancelDownload.setOnClickListener {
            cancelCurrentDownload()
        }

        binding.btnImportLocal.setOnClickListener {
            importModelLauncher.launch(arrayOf("*/*"))
        }

        binding.btnDirectDownload.setOnClickListener {
            showDirectDownloadDialog()
        }

        binding.btnOpenGithub.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(ModelManager.URL_RELEASE_PAGE))
            startActivity(intent)
        }

        binding.cardVibration.setOnClickListener {
            val nextEnabled = !prefs.isFeedbackEnabled
            prefs.isFeedbackEnabled = nextEnabled
            if (nextEnabled) {
                VibrationHelper.performPreview(this, true)
            }
            renderVibrationCard()
        }
    }

    private fun isEnvironmentReady(): Boolean {
        val serviceOk = VoiceAccessibilityService.instance != null
        val micOk = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        return serviceOk && micOk
    }

    private fun openMissingPermissionFlow() {
        if (VoiceAccessibilityService.instance == null) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        val micOk = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!micOk) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun handleModelRowClick(variant: String) {
        if (downloadJob?.isActive == true) {
            Toast.makeText(this, "正在下载模型，请先等待完成或点击取消", Toast.LENGTH_SHORT).show()
            return
        }

        val hasModel = when (variant) {
            PreferenceHelper.MODEL_VARIANT_INT8 -> ModelManager.hasInt8Model(this)
            PreferenceHelper.MODEL_VARIANT_FP16 -> ModelManager.hasFp16Model(this)
            else -> false
        }
        val hasTokens = ModelManager.hasTokens(this)

        if (hasModel && hasTokens) {
            prefs.preferredModelVariant = variant
            VoiceAccessibilityService.instance?.reloadModelIfNeeded()
            if (prefs.isFeedbackEnabled) {
                VibrationHelper.performClickVibration(this)
            }
            renderModelCard()
            val label = if (variant == PreferenceHelper.MODEL_VARIANT_INT8) "INT8 轻量版" else "FP16 满血版"
            Toast.makeText(this, "已切换为 $label", Toast.LENGTH_SHORT).show()
        } else {
            startModelDownload(variant)
        }
    }

    private fun showDirectDownloadDialog() {
        if (downloadJob?.isActive == true) {
            Toast.makeText(this, "正在下载中，请稍候或先取消当前下载", Toast.LENGTH_SHORT).show()
            return
        }

        val int8Tag = if (ModelManager.hasInt8Model(this)) "（已安装，点此重新下载）" else "（未安装）"
        val fp16Tag = if (ModelManager.hasFp16Model(this)) "（已安装，点此重新下载）" else "（未安装）"
        val items = arrayOf(
            "SenseVoice INT8 轻量版 · 约 228 MB $int8Tag",
            "SenseVoice FP16 满血版 · 约 894 MB $fp16Tag"
        )

        AlertDialog.Builder(this)
            .setTitle("选择要在线下载安装的模型")
            .setItems(items) { _, which ->
                val variant = if (which == 0) {
                    PreferenceHelper.MODEL_VARIANT_INT8
                } else {
                    PreferenceHelper.MODEL_VARIANT_FP16
                }
                startModelDownload(variant, forceRedownload = true)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startModelDownload(variant: String, forceRedownload: Boolean = false) {
        downloadingVariant = variant
        binding.layoutDownloadProgress.visibility = View.VISIBLE
        binding.progressDownload.progress = 0
        binding.tvDownloadStatus.text = "准备下载..."
        renderModelCard()

        downloadJob = lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val dir = ModelManager.getModelDirectory(this@MainActivity)

                    // 1. 确保 tokens.txt 词典就绪
                    if (forceRedownload || !ModelManager.hasTokens(this@MainActivity)) {
                        withContext(Dispatchers.Main) {
                            binding.tvDownloadStatus.text = "正在下载词典 (tokens.txt)..."
                        }
                        val tokensDest = File(dir, ModelManager.TOKENS_NAME)
                        downloadFileWithRedirects(ModelManager.URL_TOKENS, tokensDest) { _, _ -> }
                    }

                    // 2. 下载选定版本的模型权重
                    val (modelUrl, targetFile, title) = if (variant == PreferenceHelper.MODEL_VARIANT_INT8) {
                        Triple(
                            ModelManager.URL_MODEL_INT8,
                            ModelManager.getInt8ModelFile(this@MainActivity),
                            "INT8 轻量版"
                        )
                    } else {
                        Triple(
                            ModelManager.URL_MODEL_FP16,
                            ModelManager.getFp16ModelFile(this@MainActivity),
                            "FP16 满血版"
                        )
                    }

                    if (forceRedownload || !targetFile.exists() || targetFile.length() == 0L) {
                        var lastUiUpdate = 0L
                        downloadFileWithRedirects(modelUrl, targetFile) { downloadedBytes, totalBytes ->
                            val now = System.currentTimeMillis()
                            if (now - lastUiUpdate >= 120L || downloadedBytes == totalBytes) {
                                lastUiUpdate = now
                                val downloadedMb = downloadedBytes / (1024f * 1024f)
                                val totalMb = if (totalBytes > 0) totalBytes / (1024f * 1024f) else 0f
                                val pct = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
                                runOnUiThread {
                                    binding.progressDownload.isIndeterminate = totalBytes <= 0L
                                    binding.progressDownload.progress = pct
                                    binding.tvDownloadStatus.text = if (totalBytes > 0L) {
                                        String.format("正在下载 %s · %.1f / %.1f MB (%d%%)", title, downloadedMb, totalMb, pct)
                                    } else {
                                        String.format("正在下载 %s · %.1f MB", title, downloadedMb)
                                    }
                                }
                            }
                        }
                    }
                }

                prefs.preferredModelVariant = variant
                VoiceAccessibilityService.instance?.reloadModelIfNeeded()
                downloadingVariant = null
                binding.layoutDownloadProgress.visibility = View.GONE
                renderAll()
                Toast.makeText(this@MainActivity, "模型下载并安装完成，已激活使用", Toast.LENGTH_SHORT).show()
            } catch (_: CancellationException) {
                downloadingVariant = null
                binding.layoutDownloadProgress.visibility = View.GONE
                renderModelCard()
                Toast.makeText(this@MainActivity, "已取消下载", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                downloadingVariant = null
                binding.layoutDownloadProgress.visibility = View.GONE
                renderModelCard()
                Toast.makeText(
                    this@MainActivity,
                    "下载失败，请确认网络或已开启 VPN (${e.message ?: "网络异常"})",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun cancelCurrentDownload() {
        downloadJob?.cancel()
        downloadJob = null
    }

    /**
     * 支持自动跟踪 HuggingFace 301/302/307/308 重定向的流式文件下载器
     */
    private suspend fun downloadFileWithRedirects(
        startUrl: String,
        destFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ) {
        val tempFile = File(destFile.parentFile, "${destFile.name}.tmp")
        if (tempFile.exists()) tempFile.delete()

        var connection: HttpURLConnection? = null
        try {
            var currentUrl = startUrl
            var redirectCount = 0
            while (true) {
                coroutineContext.ensureActive()
                val url = URL(currentUrl)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15000
                    readTimeout = 30000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "CaretASR/1.0")
                }
                val code = connection.responseCode
                if (code in 301..303 || code == 307 || code == 308) {
                    val location = connection.getHeaderField("Location")
                        ?: throw IllegalStateException("重定向地址为空")
                    connection.disconnect()
                    currentUrl = URL(url, location).toString()
                    redirectCount++
                    if (redirectCount > 8) {
                        throw IllegalStateException("重定向次数过多")
                    }
                    continue
                }
                if (code !in 200..299) {
                    throw IllegalStateException("HTTP $code")
                }
                break
            }

            val conn = connection ?: throw IllegalStateException("连接失败")
            val totalBytes = conn.contentLengthLong
            var downloadedBytes = 0L

            conn.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        onProgress(downloadedBytes, totalBytes)
                    }
                    output.flush()
                }
            }

            if (downloadedBytes <= 0L) {
                throw IllegalStateException("下载内容为空")
            }
            if (destFile.exists()) {
                destFile.delete()
            }
            if (!tempFile.renameTo(destFile)) {
                tempFile.copyTo(destFile, overwrite = true)
                tempFile.delete()
            }
        } catch (t: Throwable) {
            if (tempFile.exists()) {
                tempFile.delete()
            }
            throw t
        } finally {
            connection?.disconnect()
        }
    }

    private fun renderAll() {
        renderStatusCard()
        renderModelCard()
        renderVibrationCard()
    }

    private fun renderStatusCard() {
        val serviceOk = VoiceAccessibilityService.instance != null
        val micOk = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        when {
            !serviceOk -> {
                binding.viewStatusDot.setBackgroundResource(R.drawable.bg_dot_copper)
                binding.tvStatusTitle.text = "无障碍服务未开启"
                binding.tvStatusSub.text = "需要开启后才能监听左侧键并填入文字"
                binding.btnStatusAction.visibility = View.VISIBLE
                binding.btnStatusAction.text = "去开启"
            }
            !micOk -> {
                binding.viewStatusDot.setBackgroundResource(R.drawable.bg_dot_copper)
                binding.tvStatusTitle.text = "麦克风权限未授权"
                binding.tvStatusSub.text = "仅在本机离线录音识别，不出设备"
                binding.btnStatusAction.visibility = View.VISIBLE
                binding.btnStatusAction.text = "去授权"
            }
            else -> {
                binding.viewStatusDot.setBackgroundResource(R.drawable.bg_minimal_pill_active)
                binding.tvStatusTitle.text = "运行中"
                binding.tvStatusSub.text = "按一下左侧独立键开始说话，再按一下填入光标"
                binding.btnStatusAction.visibility = View.GONE
            }
        }
    }

    private fun renderModelCard() {
        val hasTokens = ModelManager.hasTokens(this)
        val hasInt8 = ModelManager.hasInt8Model(this)
        val hasFp16 = ModelManager.hasFp16Model(this)
        val activeVariant = if (hasTokens) ModelManager.getActiveVariant(this) else null

        binding.tvModelSummary.text = ModelManager.getModelDescription(this)

        val isDownloadingInt8 = downloadingVariant == PreferenceHelper.MODEL_VARIANT_INT8
        val isDownloadingFp16 = downloadingVariant == PreferenceHelper.MODEL_VARIANT_FP16

        // INT8 行状态
        when {
            isDownloadingInt8 -> {
                binding.badgeModelInt8.text = "下载中"
                binding.badgeModelInt8.setBackgroundResource(R.drawable.bg_minimal_pill_idle)
                binding.badgeModelInt8.setTextColor(Color.parseColor("#FF7A7A7A"))
            }
            activeVariant == PreferenceHelper.MODEL_VARIANT_INT8 -> {
                binding.badgeModelInt8.text = "使用中"
                binding.badgeModelInt8.setBackgroundResource(R.drawable.bg_minimal_pill_active)
                binding.badgeModelInt8.setTextColor(Color.WHITE)
            }
            hasInt8 && hasTokens -> {
                binding.badgeModelInt8.text = "切换"
                binding.badgeModelInt8.setBackgroundResource(R.drawable.bg_minimal_pill_idle)
                binding.badgeModelInt8.setTextColor(Color.parseColor("#FF111111"))
            }
            else -> {
                binding.badgeModelInt8.text = "直接下载"
                binding.badgeModelInt8.setBackgroundResource(R.drawable.bg_minimal_pill_idle)
                binding.badgeModelInt8.setTextColor(Color.parseColor("#FF111111"))
            }
        }

        // FP16 行状态
        when {
            isDownloadingFp16 -> {
                binding.badgeModelFp16.text = "下载中"
                binding.badgeModelFp16.setBackgroundResource(R.drawable.bg_minimal_pill_idle)
                binding.badgeModelFp16.setTextColor(Color.parseColor("#FF7A7A7A"))
            }
            activeVariant == PreferenceHelper.MODEL_VARIANT_FP16 -> {
                binding.badgeModelFp16.text = "使用中"
                binding.badgeModelFp16.setBackgroundResource(R.drawable.bg_minimal_pill_active)
                binding.badgeModelFp16.setTextColor(Color.WHITE)
            }
            hasFp16 && hasTokens -> {
                binding.badgeModelFp16.text = "切换"
                binding.badgeModelFp16.setBackgroundResource(R.drawable.bg_minimal_pill_idle)
                binding.badgeModelFp16.setTextColor(Color.parseColor("#FF111111"))
            }
            else -> {
                binding.badgeModelFp16.text = "直接下载"
                binding.badgeModelFp16.setBackgroundResource(R.drawable.bg_minimal_pill_idle)
                binding.badgeModelFp16.setTextColor(Color.parseColor("#FF111111"))
            }
        }
    }

    private fun renderVibrationCard() {
        val enabled = prefs.isFeedbackEnabled
        if (enabled) {
            binding.badgeVibration.text = "开启"
            binding.badgeVibration.setBackgroundResource(R.drawable.bg_minimal_pill_active)
            binding.badgeVibration.setTextColor(Color.WHITE)
        } else {
            binding.badgeVibration.text = "关闭"
            binding.badgeVibration.setBackgroundResource(R.drawable.bg_minimal_pill_idle)
            binding.badgeVibration.setTextColor(Color.parseColor("#FF555555"))
        }
    }

    private fun copyImportedFiles(uris: List<Uri>) {
        lifecycleScope.launch(Dispatchers.IO) {
            val modelDir = ModelManager.getModelDirectory(this@MainActivity)
            var count = 0
            var importedVariant: String? = null
            uris.forEach { uri ->
                val rawName = getFileName(uri) ?: "imported_${System.currentTimeMillis()}"
                val destName = when {
                    rawName == ModelManager.MODEL_INT8_NAME || rawName.contains("int8", ignoreCase = true) && rawName.endsWith(".onnx") -> {
                        importedVariant = PreferenceHelper.MODEL_VARIANT_INT8
                        ModelManager.MODEL_INT8_NAME
                    }
                    rawName == ModelManager.MODEL_FP16_NAME || rawName.endsWith(".onnx") -> {
                        importedVariant = PreferenceHelper.MODEL_VARIANT_FP16
                        ModelManager.MODEL_FP16_NAME
                    }
                    rawName.contains("tokens", ignoreCase = true) -> ModelManager.TOKENS_NAME
                    else -> rawName
                }
                val destFile = File(modelDir, destName)
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (destName.endsWith(".onnx")) {
                        prefs.customModelPath = destFile.absolutePath
                        count++
                    } else if (destName == ModelManager.TOKENS_NAME) {
                        prefs.customTokensPath = destFile.absolutePath
                        count++
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            importedVariant?.let { prefs.preferredModelVariant = it }

            withContext(Dispatchers.Main) {
                VoiceAccessibilityService.instance?.reloadModelIfNeeded()
                renderAll()
                Toast.makeText(this@MainActivity, "已导入 $count 个文件", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    name = it.getString(index)
                }
            }
        }
        return name
    }
}
