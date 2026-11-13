package com.lucas.asrcapsule.aidl;

import com.lucas.asrcapsule.aidl.IASRCallback;

interface IASRService {
    /**
     * 预热/初始化模型
     * @param modelPath 模型文件路径 (SenseVoice model.onnx 或 model.int8.onnx)
     * @param tokensPath tokens.txt 字典路径
     */
    void initModel(String modelPath, String tokensPath);

    /**
     * 识别音频数据 (内存传递短音频)
     */
    void recognizeSamples(in float[] samples, int sampleRate, IASRCallback callback);

    /**
     * 识别指定 PCM 临时文件 (适合较长语音，避免 Binder 事务超限)
     */
    void recognizeFile(String pcmFilePath, int sampleRate, IASRCallback callback);

    /**
     * 自毁终止进程，100% 释放内存
     */
    void killSelf();
}
