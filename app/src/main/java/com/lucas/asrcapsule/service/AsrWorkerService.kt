package com.lucas.asrcapsule.service

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.lucas.asrcapsule.aidl.IASRCallback
import com.lucas.asrcapsule.aidl.IASRService
import com.lucas.asrcapsule.audio.PcmUtils
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.system.exitProcess

/**
 * 运行在独立进程 (:asr_worker) 的 ASR 推理服务
 * 职责：按需拉起、加载 SenseVoice 模型、极速推理、用完即焚 (0.5s 内主动自毁)
 */
class AsrWorkerService : Service() {

    companion object {
        private const val TAG = "AsrWorkerService"
        private const val KILL_DELAY_MS = 800L
    }

    private var recognizer: OfflineRecognizer? = null
    private var currentModelPath: String? = null
    private var currentTokensPath: String? = null

    private val singleExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val killRunnable = Runnable {
        Log.i(TAG, "ASR 推理完成且空闲超时，执行阅后即焚自杀，归还 100% 内存到内核")
        cleanupAndSuicide()
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "AsrWorkerService 在独立进程 :asr_worker 中已启动，PID=${Process.myPid()}")
    }

    private fun cancelKillTimer() {
        mainHandler.removeCallbacks(killRunnable)
    }

    private fun scheduleKillTimer() {
        mainHandler.removeCallbacks(killRunnable)
        mainHandler.postDelayed(killRunnable, KILL_DELAY_MS)
    }

    private fun ensureRecognizerInitialized(modelPath: String, tokensPath: String): Boolean {
        if (recognizer != null && currentModelPath == modelPath && currentTokensPath == tokensPath) {
            return true
        }

        val modelFile = File(modelPath)
        val tokensFile = File(tokensPath)
        if (!modelFile.exists() || !tokensFile.exists()) {
            Log.e(TAG, "模型或字典文件不存在: model=$modelPath, tokens=$tokensPath")
            return false
        }

        try {
            val startTime = System.currentTimeMillis()
            Log.i(TAG, "正在通过 mmap/sherpa-onnx 加载 SenseVoice 模型...")

            // 关键优化：中文日常输入场景必须指定 language = "zh"
            // 严禁设为 "auto"，否则短句极高概率被多语言分类头误判为日/韩/粤语导致识别精度断崖式下降
            val senseVoiceConfig = OfflineSenseVoiceModelConfig(
                model = modelPath,
                language = "zh",
                useInverseTextNormalization = true
            )

            // Snapdragon 8 Gen 5 性能旗舰，配置 4 线程并发推理
            val modelConfig = OfflineModelConfig(
                senseVoice = senseVoiceConfig,
                tokens = tokensPath,
                modelType = "sense-voice",
                numThreads = 4,
                debug = false
            )

            val recognizerConfig = OfflineRecognizerConfig(
                modelConfig = modelConfig
            )

            recognizer?.release()
            // 关键：从本地绝对路径加载模型时，assetManager 必须传 null (否则 JNI 层会尝试以 AssetManager 读取导致崩溃)
            recognizer = OfflineRecognizer(assetManager = null, config = recognizerConfig)
            currentModelPath = modelPath
            currentTokensPath = tokensPath

            val cost = System.currentTimeMillis() - startTime
            Log.i(TAG, "SenseVoice 模型极速加载成功，耗时: ${cost}ms")
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "初始化 SenseVoice 模型失败: ${e.message}", e)
            // 如果尝试加载满血版模型失败，且存在 int8 模型，自动无缝降级加载 int8
            if (!modelPath.contains("int8")) {
                val fallbackFile = File(modelFile.parentFile, "model.int8.onnx")
                if (fallbackFile.exists()) {
                    Log.w(TAG, "尝试自动降级到量化版 model.int8.onnx 模型...")
                    return ensureRecognizerInitialized(fallbackFile.absolutePath, tokensPath)
                }
            }
            recognizer = null
            return false
        }
    }

    private val binder = object : IASRService.Stub() {
        override fun initModel(modelPath: String?, tokensPath: String?) {
            cancelKillTimer()
            if (modelPath != null && tokensPath != null) {
                singleExecutor.execute {
                    ensureRecognizerInitialized(modelPath, tokensPath)
                }
            }
        }

        override fun recognizeSamples(samples: FloatArray?, sampleRate: Int, callback: IASRCallback?) {
            cancelKillTimer()
            if (samples == null || samples.isEmpty()) {
                callback?.onAsrError("音频数据为空")
                scheduleKillTimer()
                return
            }

            singleExecutor.execute {
                doInference(samples, sampleRate, callback)
            }
        }

        override fun recognizeFile(pcmFilePath: String?, sampleRate: Int, callback: IASRCallback?) {
            cancelKillTimer()
            if (pcmFilePath == null) {
                callback?.onAsrError("音频文件路径为空")
                scheduleKillTimer()
                return
            }

            val pcmFile = File(pcmFilePath)
            if (!pcmFile.exists() || pcmFile.length() == 0L) {
                callback?.onAsrError("PCM 文件不存在或为空")
                scheduleKillTimer()
                return
            }

            singleExecutor.execute {
                try {
                    val bytes = FileInputStream(pcmFile).use { it.readBytes() }
                    val samples = PcmUtils.pcm16ToFloatArray(bytes)
                    doInference(samples, sampleRate, callback)
                } catch (e: Exception) {
                    callback?.onAsrError("读取 PCM 文件异常: ${e.message}")
                    scheduleKillTimer()
                } finally {
                    try {
                        pcmFile.delete()
                    } catch (_: Exception) {}
                }
            }
        }

        override fun killSelf() {
            cleanupAndSuicide()
        }
    }

    private fun doInference(samples: FloatArray, sampleRate: Int, callback: IASRCallback?) {
        val recognizerInstance = recognizer
        if (recognizerInstance == null) {
            callback?.onAsrError("ASR 推理引擎尚未就绪或模型未加载")
            scheduleKillTimer()
            return
        }

        try {
            val startTime = System.currentTimeMillis()
            val stream = recognizerInstance.createStream()
            stream.acceptWaveform(samples, sampleRate)
            recognizerInstance.decode(stream)
            val result = recognizerInstance.getResult(stream)
            stream.release()

            val rawText = result.text.trim()
            // 过滤 SenseVoice 特殊标签 (如 <|zh|><|NEUTRAL|><|Speech|> 等)，确保纯文本上屏
            val text = rawText.replace(Regex("<\\|.*?\\|>"), "").trim()
            val cost = System.currentTimeMillis() - startTime
            Log.i(TAG, "SenseVoice 推理完毕，耗时: ${cost}ms，识别内容: \"$text\" (原始: \"$rawText\")")

            callback?.onAsrSuccess(text)
        } catch (e: Throwable) {
            Log.e(TAG, "推理过程异常: ${e.message}", e)
            callback?.onAsrError("离线推理异常: ${e.message}")
        } finally {
            // 启动 0.5s 倒计时自毁
            scheduleKillTimer()
        }
    }

    private fun cleanupAndSuicide() {
        try {
            recognizer?.release()
            recognizer = null
            singleExecutor.shutdownNow()
        } catch (_: Exception) {}
        stopSelf()
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        cleanupAndSuicide()
    }
}
