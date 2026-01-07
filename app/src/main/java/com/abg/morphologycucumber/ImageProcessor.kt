package com.abg.morphologycucumber

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

class ImageProcessor(private val context: Context) {

    companion object {
        private const val A4_WIDTH_MM = 210f
        private const val A4_HEIGHT_MM = 297f
    }

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
            Log.d("ImageProcessor", "Размер srcMat: ${srcMat.width()}x${srcMat.height()}, каналы: ${srcMat.channels()}")

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
                // Создаем простую визуализацию без огурца
                val debugBitmap = createSimpleDebugBitmap(srcMat, paperRect)
                return ProcessedResult(
                    MeasurementResult(0f, 0f, 0f, 0f, "Не удалось найти огурец"),
                    null, null, debugBitmap
                )
            }

            // 6. Вычисляем измерения
            val measurements = calculateMeasurements(cucumberContour.first, scale)

            // 7. Создаем Bitmap с визуализацией
            val debugBitmap = createDebugBitmap(srcMat, paperRect, cucumberContour.first)

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
     * Простой метод нахождения листа A4
     */
    private fun findPaperRectSimple(srcMat: Mat): Rect {
        // Предполагаем, что лист занимает центральную часть с небольшими отступами
        val marginX = max(20, srcMat.width() / 20)
        val marginY = max(20, srcMat.height() / 20)

        return Rect(
            marginX,
            marginY,
            srcMat.width() - 2 * marginX,
            srcMat.height() - 2 * marginY
        )
    }

    /**
     * Поиск огурца
     */
    private fun findCucumberWithVisualization(region: Mat): Pair<MatOfPoint, Mat> {
        val mask = Mat.zeros(region.size(), CvType.CV_8UC1)

        try {
            // 1. Конвертируем в градации серого
            val gray = Mat()
            Imgproc.cvtColor(region, gray, Imgproc.COLOR_BGR2GRAY)

            // 2. Пороговая обработка для выделения темных областей (огурец обычно темнее фона)
            val binary = Mat()
            Imgproc.threshold(gray, binary, 0.0, 255.0,
                Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)

            // 3. Морфологические операции для улучшения маски
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
            Imgproc.morphologyEx(binary, mask, Imgproc.MORPH_CLOSE, kernel)
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel)

            // 4. Находим контуры
            val contours = mutableListOf<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(mask, contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            Log.d("ImageProcessor", "Найдено контуров: ${contours.size}")

            if (contours.isEmpty()) {
                return Pair(MatOfPoint(), mask)
            }

            // 5. Ищем самый большой контур
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
     * Создание простого отладочного Bitmap (без огурца)
     */
    private fun createSimpleDebugBitmap(srcMat: Mat, paperRect: Rect): Bitmap {
        try {
            // Создаем копию исходного изображения
            val resultMat = Mat()
            srcMat.copyTo(resultMat)

            // Рисуем прямоугольник листа (синий)
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

            // Добавляем текст
            val text = "Лист A4 определен"
            Imgproc.putText(
                resultMat,
                text,
                Point(paperRect.x.toDouble(), paperRect.y.toDouble() - 10),
                Imgproc.FONT_HERSHEY_SIMPLEX,
                0.7,
                Scalar(255.0, 0.0, 0.0), // BGR: синий
                2
            )

            // Конвертируем обратно в Bitmap
            val resultBitmap = Bitmap.createBitmap(
                resultMat.width(), resultMat.height(), Bitmap.Config.ARGB_8888
            )
            Utils.matToBitmap(resultMat, resultBitmap)

            return resultBitmap

        } catch (e: Exception) {
            Log.e("ImageProcessor", "Ошибка создания простого debug bitmap", e)
            // Возвращаем пустой Bitmap в случае ошибки
            return Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.RED)
            }
        }
    }

    /**
     * Создание отладочного Bitmap с визуализацией огурца
     */
    private fun createDebugBitmap(srcMat: Mat, paperRect: Rect, cucumberContour: MatOfPoint): Bitmap {
        return try {
            Log.d("ImageProcessor", "Создание debug bitmap")
            Log.d("ImageProcessor", "Размер srcMat: ${srcMat.width()}x${srcMat.height()}")
            Log.d("ImageProcessor", "Размер paperRect: ${paperRect.width}x${paperRect.height}")

            // Создаем копию исходного изображения
            val resultMat = Mat()
            srcMat.copyTo(resultMat)

            // Убедимся, что изображение имеет 3 канала (BGR)
            if (resultMat.channels() == 1) {
                Imgproc.cvtColor(resultMat, resultMat, Imgproc.COLOR_GRAY2BGR)
            } else if (resultMat.channels() == 4) {
                Imgproc.cvtColor(resultMat, resultMat, Imgproc.COLOR_BGRA2BGR)
            }

            Log.d("ImageProcessor", "Каналы resultMat: ${resultMat.channels()}")

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

            // 2. Подготавливаем контур огурца в глобальных координатах
            val globalContourPoints = mutableListOf<Point>()
            val localPoints = cucumberContour.toList()

            for (point in localPoints) {
                globalContourPoints.add(
                    Point(
                        point.x + paperRect.x,
                        point.y + paperRect.y
                    )
                )
            }

            val globalContour = MatOfPoint()
            globalContour.fromList(globalContourPoints)

            // 3. Создаем маску для заливки области огурца
            val fillMask = Mat.zeros(resultMat.size(), CvType.CV_8UC1)

            // Рисуем контур на маске (белым цветом)
            Imgproc.drawContours(
                fillMask,
                listOf(globalContour),
                -1,
                Scalar(255.0),
                -1
            )

            // 4. Создаем цветную заливку (зеленый)
            val colorFill = Mat(resultMat.size(), resultMat.type(), Scalar(0.0, 255.0, 0.0))

            // 5. Применяем заливку только к области маски
            colorFill.copyTo(resultMat, fillMask)

            // 6. Рисуем контур огурца (темно-зеленый) поверх заливки
            Imgproc.drawContours(
                resultMat,
                listOf(globalContour),
                -1,
                Scalar(0.0, 150.0, 0.0), // Темно-зеленый
                3
            )

            // 7. Рисуем ограничивающий прямоугольник (красный)
            val boundingRect = Imgproc.boundingRect(globalContour)
            Imgproc.rectangle(
                resultMat,
                boundingRect.tl(),
                boundingRect.br(),
                Scalar(0.0, 0.0, 255.0), // BGR: красный
                2
            )

            // 8. Добавляем текст с размерами
            val text = "ОГУРЕЦ"
            drawTextOnMat(
                resultMat,
                text,
                Point(boundingRect.x.toDouble(), boundingRect.y.toDouble()),
                24f, // размер текста
                Scalar(0.0, 255.0, 255.0)
            )

            // 9. Конвертируем обратно в Bitmap
            val resultBitmap = Bitmap.createBitmap(
                resultMat.width(), resultMat.height(), Bitmap.Config.ARGB_8888
            )
            Utils.matToBitmap(resultMat, resultBitmap)

            Log.d("ImageProcessor", "Debug bitmap создан успешно")
            resultBitmap

        } catch (e: Exception) {
            Log.e("ImageProcessor", "Ошибка создания debug bitmap", e)
            e.printStackTrace()
            // Возвращаем простой красный Bitmap в случае ошибки
            Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.RED)
            }
        }
    }

    fun drawTextOnMat(mat: Mat, text: String, point: Point, textSizeZ: Float, color: Scalar) {
        // Создаем Bitmap из Mat
        val bitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, bitmap)

        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            this.color = Color.argb(
                255,
                color.`val`[2].toInt(), // R
                color.`val`[1].toInt(), // G
                color.`val`[0].toInt()  // B
            )
            textSize = textSizeZ * context.resources.displayMetrics.scaledDensity
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }

        // Рисуем текст
        canvas.drawText(text, point.x.toFloat(), point.y.toFloat(), paint)

        // Конвертируем Bitmap обратно в Mat
        Utils.bitmapToMat(bitmap, mat)
        bitmap.recycle()
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
        // Получаем ограничивающий прямоугольник
        val rect = Imgproc.boundingRect(contour)

        // Длина и ширина из bounding rect
        val lengthPx = max(rect.width, rect.height).toFloat()
        val widthPx = min(rect.width, rect.height).toFloat()

        // В миллиметрах
        val lengthMm = lengthPx / scale
        val widthMm = widthPx / scale
        val diameterMm = widthMm

        // Объем (цилиндр)
        val volumeMm3 = (Math.PI * (diameterMm / 2) * (diameterMm / 2) * lengthMm).toFloat()

        return MeasurementResult(
            length = lengthMm,
            width = widthMm,
            diameter = diameterMm,
            volume = volumeMm3
        )
    }

    /**
     * Вспомогательная функция для конвертации MatOfPoint в список
     */
    private fun MatOfPoint.toList(): List<Point> {
        val points = mutableListOf<Point>()
        val total = this.total().toInt()

        if (total > 0) {
            val tempArray = this.toArray()
            for (i in 0 until min(tempArray.size, total)) {
                points.add(tempArray[i])
            }
        }

        return points
    }

    /**
     * Вспомогательная функция для получения массива из MatOfPoint
     */
    private fun MatOfPoint.toArray(): Array<Point> {
        val total = this.total().toInt()
        if (total == 0) return emptyArray()

        val points = Array(total) { Point() }
        for (i in 0 until total) {
            val pointArray = this.get(i, 0)
            if (pointArray.size >= 2) {
                points[i] = Point(pointArray[0], pointArray[1])
            }
        }
        return points
    }
}