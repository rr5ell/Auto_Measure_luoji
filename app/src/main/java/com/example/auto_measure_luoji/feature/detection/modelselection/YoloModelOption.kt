package com.example.auto_measure_luoji.feature.detection.modelselection

/** 仅开放已随 APK 打包、与当前旗杆解析器配套的模型，不接收任意文件路径。 */
enum class YoloModelOption(val assetPath: String, val label: String) {
    V35("bestV35_float32.tflite", "V35"),
    V34("bestV34_float32.tflite", "V34"),
    V29("bestV29_float32.tflite", "V29");

    companion object {
        const val PREFERENCE_KEY = "yolo_model_asset"
        fun fromAssetPath(path: String?): YoloModelOption =
            values().firstOrNull { it.assetPath == path } ?: V34
    }
}
