package com.lucas.asrcapsule.util

import android.content.Context
import android.content.SharedPreferences

class PreferenceHelper(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("local_asr_capsule_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_VIBRATION_ENABLED = "vibration_enabled"
        private const val KEY_CUSTOM_MODEL_PATH = "custom_model_path"
        private const val KEY_CUSTOM_TOKENS_PATH = "custom_tokens_path"
        private const val KEY_PREFERRED_MODEL = "preferred_model_variant"

        const val MODEL_VARIANT_AUTO = "auto"
        const val MODEL_VARIANT_INT8 = "int8"
        const val MODEL_VARIANT_FP16 = "fp16"
        const val MODEL_VARIANT_CUSTOM = "custom"
    }

    /**
     * 首选模型版本："auto" / "int8" / "fp16" / "custom"
     */
    var preferredModelVariant: String
        get() = prefs.getString(KEY_PREFERRED_MODEL, MODEL_VARIANT_AUTO) ?: MODEL_VARIANT_AUTO
        set(value) = prefs.edit().putString(KEY_PREFERRED_MODEL, value).apply()

    /**
     * 是否开启声触反馈 (声震一体 · 自动遵循系统静音模式)
     */
    var isFeedbackEnabled: Boolean
        get() = prefs.getBoolean(KEY_VIBRATION_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_VIBRATION_ENABLED, value).apply()

    /**
     * 自定义模型绝对路径
     */
    var customModelPath: String?
        get() = prefs.getString(KEY_CUSTOM_MODEL_PATH, null)
        set(value) = prefs.edit().putString(KEY_CUSTOM_MODEL_PATH, value).apply()

    /**
     * 自定义 tokens.txt 字典绝对路径
     */
    var customTokensPath: String?
        get() = prefs.getString(KEY_CUSTOM_TOKENS_PATH, null)
        set(value) = prefs.edit().putString(KEY_CUSTOM_TOKENS_PATH, value).apply()
}
