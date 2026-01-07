package com.abg.morphologycucumber

import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.RotatedRect

/**
 * Результат измерения огурца
 */
data class MeasurementResult(
    val length: Float,        // Длина в мм
    val width: Float,         // Ширина в мм
    val diameter: Float,      // Диаметр в мм (средний)
    val volume: Float,        // Объем в мм³
    val error: String? = null // Сообщение об ошибке, если есть
)


/**
 * Анализ контура огурца
 */
data class ContourAnalysis(
    val rotatedRect: RotatedRect,
    val boundingRect: Rect,
    val area: Double
)