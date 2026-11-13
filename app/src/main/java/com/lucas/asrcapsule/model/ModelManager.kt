package com.lucas.asrcapsule.model

import android.content.Context
import com.lucas.asrcapsule.util.PreferenceHelper
import java.io.File

object ModelManager {

    private const val MODEL_FOLDER = "sense-voice"
    const val MODEL_INT8_NAME = "model.int8.onnx"
    const val MODEL_FP16_NAME = "model.onnx"
    const val TOKENS_NAME = "tokens.txt"

    const val URL_TOKENS =
        "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/tokens.txt"
    const val URL_MODEL_INT8 =
        "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx"
    const val URL_MODEL_FP16 =
        "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.onnx"
    const val URL_RELEASE_PAGE =
        "https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models"

    /**
     * 获取存储模型的标准应用私有目录
     */
    fun getModelDirectory(context: Context): File {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(baseDir, MODEL_FOLDER)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getInt8ModelFile(context: Context): File = File(getModelDirectory(context), MODEL_INT8_NAME)

    fun getFp16ModelFile(context: Context): File = File(getModelDirectory(context), MODEL_FP16_NAME)

    fun hasInt8Model(context: Context): Boolean {
        val f = getInt8ModelFile(context)
        return f.exists() && f.length() > 0
    }

    fun hasFp16Model(context: Context): Boolean {
        val f = getFp16ModelFile(context)
        return f.exists() && f.length() > 0
    }

    fun hasTokens(context: Context): Boolean = getTokensFile(context) != null

    /**
     * 解析当前可用的模型文件 (优先尊重用户选择的版本，未指定时优先 fp16 再 int8)
     */
    fun getModelFile(context: Context): File? {
        val prefs = PreferenceHelper(context)
        val dir = getModelDirectory(context)
        val fp16File = File(dir, MODEL_FP16_NAME)
        val int8File = File(dir, MODEL_INT8_NAME)
        val customFile = prefs.customModelPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0 }

        when (prefs.preferredModelVariant) {
            PreferenceHelper.MODEL_VARIANT_INT8 -> {
                if (int8File.exists() && int8File.length() > 0) return int8File
            }
            PreferenceHelper.MODEL_VARIANT_FP16 -> {
                if (fp16File.exists() && fp16File.length() > 0) return fp16File
            }
            PreferenceHelper.MODEL_VARIANT_CUSTOM -> {
                if (customFile != null) return customFile
            }
        }

        if (customFile != null && prefs.preferredModelVariant == PreferenceHelper.MODEL_VARIANT_AUTO) {
            return customFile
        }
        if (fp16File.exists() && fp16File.length() > 0) return fp16File
        if (int8File.exists() && int8File.length() > 0) return int8File
        if (customFile != null) return customFile

        return null
    }

    /**
     * 解析当前激活的模型变体标识 ("int8" / "fp16" / "custom" / null)
     */
    fun getActiveVariant(context: Context): String? {
        val file = getModelFile(context) ?: return null
        return when (file.name) {
            MODEL_INT8_NAME -> PreferenceHelper.MODEL_VARIANT_INT8
            MODEL_FP16_NAME -> PreferenceHelper.MODEL_VARIANT_FP16
            else -> PreferenceHelper.MODEL_VARIANT_CUSTOM
        }
    }

    /**
     * 解析当前可用的 tokens.txt 字典文件
     */
    fun getTokensFile(context: Context): File? {
        val prefs = PreferenceHelper(context)
        prefs.customTokensPath?.let { customPath ->
            val file = File(customPath)
            if (file.exists() && file.length() > 0) return file
        }

        val dir = getModelDirectory(context)
        val tokensFile = File(dir, TOKENS_NAME)
        if (tokensFile.exists() && tokensFile.length() > 0) return tokensFile

        return null
    }

    /**
     * 检查 SenseVoice 模型与字典是否完整就绪
     */
    fun isModelReady(context: Context): Boolean {
        val model = getModelFile(context)
        val tokens = getTokensFile(context)
        return model != null && tokens != null
    }

    /**
     * 获取模型描述摘要
     */
    fun getModelDescription(context: Context): String {
        val model = getModelFile(context)
        val tokens = getTokensFile(context)
        return if (model != null && tokens != null) {
            val sizeMb = model.length() / (1024 * 1024)
            val type = when (model.name) {
                MODEL_FP16_NAME -> "FP16 满血版"
                MODEL_INT8_NAME -> "INT8 轻量版"
                else -> model.name
            }
            "$type · ${sizeMb}MB"
        } else if (model != null) {
            "缺少 tokens.txt 字典"
        } else {
            "未安装模型"
        }
    }
}
