package com.lucas.asrcapsule.aidl;

interface IASRCallback {
    void onAsrSuccess(String text);
    void onAsrError(String errorMessage);
}
