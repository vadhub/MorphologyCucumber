package com.abg.morphologycucumber

import android.graphics.Bitmap
import org.opencv.core.MatOfPoint
import org.opencv.core.Rect

/**
 * Результат измерения огурца
 */
data class MeasurementResult(
    val length: Float,        // Длина в мм
    val width: Float,         // Ширина в мм
    val diameter: Float,      // Диаметр в мм (средний)
    val volume: Float,        // Объем в мм³
    val curve: Float = 0f,         // Кривизна
    val error: String? = null // Сообщение об ошибке, если есть
)

/**
 * Анализ контура огурца
 */
data class ProcessedResult(
    val measurements: MeasurementResult,
    val cucumberContour: MatOfPoint? = null,
    val paperRect: Rect? = null,
    val debugBitmap: Bitmap? = null
)
