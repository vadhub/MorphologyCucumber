package com.abg.morphologycucumber

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

class ImageProcessor {

    companion object {
        private const val A4_WIDTH_MM = 210f
        private const val A4_HEIGHT_MM = 297f
    }

    data class MeasurementResult(
        val length: Float,
        val width: Float,
        val diameter: Float,
        val volume: Float,
        val error: String? = null
    )

    data class ProcessedResult(
        val measurements: MeasurementResult,
        val cucumberContour: MatOfPoint? = null,
        val paperRect: Rect? = null,
        val debugBitmap: Bitmap? = null
    )

    fun processCucumberImage(bitmap: Bitmap): ProcessedResult {
        return try {
            Log.d("ImageProcessor", "Начало обработки изображения")

            if (bitmap.isRecycled) {
                return ProcessedResult(
                    MeasurementResult(0f, 0f, 0f, 0f, "Изображение не доступно"),
                    null, null, null
                )
            }

            // Конвертируем в ARGB_8888 если нужно
            val compatibleBitmap = if (bitmap.config != Bitmap.Config.ARGB_8888) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                bitmap
            }

            if (compatibleBitmap == null) {
                return ProcessedResult(
                    MeasurementResult(0f, 0f, 0f, 0f, "Не удалось преобразовать изображение"),
                    null, null, null
                )
            }

            // 1. Конвертируем Bitmap в Mat
            val srcMat = Mat()
            Utils.bitmapToMat(compatibleBitmap, srcMat)

            // 2. Находим лист A4 (простой метод - по краям)
            val paperRect = findPaperRectSimple(srcMat)
            Log.d("ImageProcessor", "Найден прямоугольник листа: $paperRect")

            if (paperRect.width <= 0 || paperRect.height <= 0) {
                return ProcessedResult(
                    MeasurementResult(0f, 0f, 0f, 0f, "Не удалось определить лист A4"),
                    null, null, null
                )
            }

            // 3. Получаем масштаб
            val scale = calculateScaleFromRect(paperRect)
            Log.d("ImageProcessor", "Масштаб: $scale")

            // 4. Обрезаем до области листа
            val paperRegion = Mat(srcMat, paperRect)

            // 5. Ищем огурец
            val cucumberContour = findCucumberWithVisualization(paperRegion)

            if (cucumberContour.first.empty()) {
                return ProcessedResult(
                    MeasurementResult(0f, 0f, 0f, 0f, "Не удалось найти огурец"),
                    null, null, createDebugBitmap(srcMat, paperRect, MatOfPoint())
                )
            }

            // 6. Вычисляем измерения
            val measurements = calculateMeasurements(cucumberContour.first, scale)

            // 7. Создаем Bitmap с визуализацией
            val debugBitmap = createDebugBitmap(srcMat, paperRect, cucumberContour.first, cucumberContour.second)

            // 8. Возвращаем результат
            ProcessedResult(
                measurements,
                cucumberContour.first,
                paperRect,
                debugBitmap
            )

        } catch (e: Exception) {
            Log.e("ImageProcessor", "Ошибка обработки", e)
            ProcessedResult(
                MeasurementResult(0f, 0f, 0f, 0f, "Ошибка обработки: ${e.localizedMessage}"),
                null, null, null
            )
        }
    }

    /**
     * Простой метод нахождения листа A4 (по краям изображения)
     */
    private fun findPaperRectSimple(srcMat: Mat): Rect {
        // Предполагаем, что лист занимает центральную часть с небольшими отступами
        val marginX = srcMat.width() / 20
        val marginY = srcMat.height() / 20

        return Rect(
            marginX,
            marginY,
            srcMat.width() - 2 * marginX,
            srcMat.height() - 2 * marginY
        )
    }

    /**
     * Поиск огурца с созданием маски для визуализации
     */
    private fun findCucumberWithVisualization(region: Mat): Pair<MatOfPoint, Mat> {
        val mask = Mat.zeros(region.size(), CvType.CV_8UC1)

        try {
            // 1. Конвертируем в HSV для выделения зеленого цвета
            val hsv = Mat()
            Imgproc.cvtColor(region, hsv, Imgproc.COLOR_BGR2HSV)

            // Диапазон зеленого цвета
            val lowerGreen = Scalar(35.0, 40.0, 40.0)
            val upperGreen = Scalar(85.0, 255.0, 255.0)

            val greenMask = Mat()
            Core.inRange(hsv, lowerGreen, upperGreen, greenMask)

            // 2. Также ищем темные объекты (на случай если огурец не зеленый)
            val gray = Mat()
            Imgproc.cvtColor(region, gray, Imgproc.COLOR_BGR2GRAY)

            val binary = Mat()
            Imgproc.threshold(gray, binary, 0.0, 255.0,
                Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)

            // 3. Объединяем обе маски
            Core.bitwise_or(greenMask, binary, mask)

            // 4. Морфологические операции для улучшения маски
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel)

            // 5. Находим контуры
            val contours = mutableListOf<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(mask, contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            Log.d("ImageProcessor", "Найдено контуров: ${contours.size}")

            if (contours.isEmpty()) {
                return Pair(MatOfPoint(), mask)
            }

            // 6. Ищем самый большой контур
            var maxArea = 0.0
            var largestContour: MatOfPoint? = null

            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                if (area > maxArea && area > 500) { // Фильтр мелких объектов
                    maxArea = area
                    largestContour = contour
                }
            }

            return Pair(largestContour ?: MatOfPoint(), mask)

        } catch (e: Exception) {
            Log.e("ImageProcessor", "Ошибка поиска огурца", e)
            return Pair(MatOfPoint(), mask)
        }
    }

    /**
     * Создание отладочного Bitmap с визуализацией
     */
    private fun createDebugBitmap(
        srcMat: Mat,
        paperRect: Rect,
        cucumberContour: MatOfPoint,
        cucumberMask: Mat? = null
    ): Bitmap {
        try {
            // Создаем копию исходного изображения
            val resultMat = Mat()
            srcMat.copyTo(resultMat)

            // 1. Рисуем прямоугольник листа (синий)
            Imgproc.rectangle(
                resultMat,
                Point(paperRect.x.toDouble(), paperRect.y.toDouble()),
                Point(
                    (paperRect.x + paperRect.width).toDouble(),
                    (paperRect.y + paperRect.height).toDouble()
                ),
                Scalar(255.0, 0.0, 0.0), // BGR: синий
                3
            )

            // 2. Если найден огурец, рисуем его контур и заливаем область
            if (!cucumberContour.empty()) {
                // Создаем маску для заливки области огурца
                val fillMask = Mat.zeros(resultMat.size(), CvType.CV_8UC3)

                // Конвертируем контур в координаты исходного изображения
                val globalContour = MatOfPoint()
                val points = cucumberContour.toArray()
                val globalPoints = points.map { point ->
                    Point(point.x + paperRect.x, point.y + paperRect.y)
                }.toTypedArray()
                globalContour.fromArray(*globalPoints)

                // Рисуем контур (зеленый)
                Imgproc.drawContours(
                    resultMat,
                    listOf(globalContour),
                    -1,
                    Scalar(0.0, 255.0, 0.0), // BGR: зеленый
                    3
                )

                // Заливаем область огурца полупрозрачным зеленым
                Imgproc.drawContours(
                    fillMask,
                    listOf(globalContour),
                    -1,
                    Scalar(0.0, 255.0, 0.0), // BGR: зеленый
                    -1
                )

                // Добавляем полупрозрачную заливку к исходному изображению
                Core.addWeighted(fillMask, 0.3, resultMat, 1.0, 0.0, resultMat)

                // 3. Рисуем ограничивающий прямоугольник (красный)
                val boundingRect = Imgproc.boundingRect(globalContour)
                Imgproc.rectangle(
                    resultMat,
                    boundingRect.tl(),
                    boundingRect.br(),
                    Scalar(0.0, 0.0, 255.0), // BGR: красный
                    2
                )

                // 4. Добавляем текст с размерами
                val text = "Обнаружен огурец"
                Imgproc.putText(
                    resultMat,
                    text,
                    Point(boundingRect.x.toDouble(), boundingRect.y.toDouble() - 10),
                    Imgproc.FONT_HERSHEY_SIMPLEX,
                    0.7,
                    Scalar(0.0, 255.0, 255.0), // BGR: желтый
                    2
                )
            }

            // 5. Конвертируем обратно в Bitmap
            val resultBitmap = Bitmap.createBitmap(
                resultMat.width(), resultMat.height(), Bitmap.Config.ARGB_8888
            )
            Utils.matToBitmap(resultMat, resultBitmap)

            return resultBitmap

        } catch (e: Exception) {
            Log.e("ImageProcessor", "Ошибка создания debug bitmap", e)
            // Возвращаем пустой Bitmap в случае ошибки
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
    }

    /**
     * Расчет масштаба из прямоугольника
     */
    private fun calculateScaleFromRect(rect: Rect): Float {
        val widthPx = rect.width.toFloat()
        val heightPx = rect.height.toFloat()

        // Определяем ориентацию
        val isLandscape = widthPx > heightPx

        return if (isLandscape) {
            val scaleX = widthPx / A4_HEIGHT_MM
            val scaleY = heightPx / A4_WIDTH_MM
            (scaleX + scaleY) / 2
        } else {
            val scaleX = widthPx / A4_WIDTH_MM
            val scaleY = heightPx / A4_HEIGHT_MM
            (scaleX + scaleY) / 2
        }
    }

    /**
     * Расчет измерений
     */
    private fun calculateMeasurements(contour: MatOfPoint, scale: Float): MeasurementResult {
        val rect = Imgproc.boundingRect(contour)

        // Получаем повернутый прямоугольник для более точных измерений
        val points2f = MatOfPoint2f()
        contour.convertTo(points2f, CvType.CV_32F)
        val rotatedRect = Imgproc.minAreaRect(points2f)

        // Длина и ширина
        val size = rotatedRect.size
        val lengthPx = max(size.width, size.height)
        val widthPx = min(size.width, size.height)

        // В миллиметрах
        val lengthMm = lengthPx / scale
        val widthMm = widthPx / scale
        val diameterMm = widthMm

        // Объем
        val volumeMm3 = (Math.PI * (diameterMm / 2) * (diameterMm / 2) * lengthMm).toFloat()

        return MeasurementResult(
            length = lengthMm.toFloat(),
            width = widthMm.toFloat(),
            diameter = diameterMm.toFloat(),
            volume = volumeMm3
        )
    }

    /**
     * Вспомогательная функция для конвертации MatOfPoint
     */
    private fun MatOfPoint.toArray(): Array<Point> {
        val points = mutableListOf<Point>()
        val count = this.rows()

        for (i in 0 until count) {
            val point = this.get(i, 0)
            points.add(Point(point[0], point[1]))
        }

        return points.toTypedArray()
    }
}