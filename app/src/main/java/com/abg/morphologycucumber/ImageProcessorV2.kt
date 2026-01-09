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
import androidx.core.graphics.createBitmap

class ImageProcessorV2(private val context: Context) {

    companion object {
        private const val A4_WIDTH_MM = 210f
        private const val A4_HEIGHT_MM = 297f
    }

    fun processCucumberImage(bitmap: Bitmap): ProcessedResult {
        return try {
            Log.d("ImageProcessor", "Начало обработки изображения")

            if (bitmap.isRecycled) {
                return ProcessedResult(
                    MeasurementResult(0f, 0f, 0f, 0f, 0f, "Изображение не доступно"),
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
                    MeasurementResult(0f, 0f, 0f, 0f, 0f, "Не удалось преобразовать изображение"),
                    null, null, null
                )
            }

            // 1. Конвертируем Bitmap в Mat
            val srcMat = Mat()
            Utils.bitmapToMat(compatibleBitmap, srcMat)
            Log.d(
                "ImageProcessor",
                "Размер srcMat: ${srcMat.width()}x${srcMat.height()}, каналы: ${srcMat.channels()}"
            )

            // 2. бинаризация
            val binary = convertToBinary(srcMat)

            // 3. Находим лист A4 (новый метод)
            val paperRect = detectSheet(binary)
            Log.d("ImageProcessor", "Найден прямоугольник листа: $paperRect")

            if (paperRect.width <= 0 || paperRect.height <= 0) {
                return ProcessedResult(
                    MeasurementResult(0f, 0f, 0f, 0f, 0f, "Не удалось определить лист A4"),
                    null, null, null
                )
            }

            // 4. Получаем масштаб
            val scale = calculateScaleFromRect(paperRect)
            Log.d("ImageProcessor", "Масштаб: $scale")

            // 5. Обрезаем до области листа
            val paperRegion = Mat(srcMat, paperRect)

            // 6. Ищем огурец
            val cucumberContour = findCucumberAdaptive(paperRegion)

            if (cucumberContour.empty()) {
                val debugBitmap = createSimpleDebugBitmap(srcMat, paperRect)
                return ProcessedResult(
                    MeasurementResult(0f, 0f, 0f, 0f, 0f, "Не удалось найти огурец"),
                    null, null, debugBitmap
                )
            }

            // 7. Создаем маску огурца для скелетизации
            val cucumberMask = createCucumberMask(paperRegion, cucumberContour)

            // 8. Применяем скелетизацию
            val skeleton = skeletonize(cucumberMask)

            // 9. Вычисляем измерения с использованием скелета
            val measurements = calculateMeasurementsWithSkeleton(
                cucumberContour,
                skeleton,
                scale,
                paperRect
            )

            // 10. Создаем Bitmap с визуализацией
            val debugBitmap = createDebugBitmapWithSkeleton(
                srcMat,
                paperRect,
                cucumberContour,
                skeleton
            )

            // 11. Возвращаем результат
            ProcessedResult(measurements, cucumberContour, paperRect, debugBitmap)

        } catch (e: Exception) {
            Log.e("ImageProcessor", "Ошибка обработки", e)
            ProcessedResult(
                MeasurementResult(0f, 0f, 0f, 0f, 0f, "Ошибка обработки: ${e.localizedMessage}"),
                null, null, null
            )
        }
    }

    // === НОВАЯ ФУНКЦИЯ: Создание маски огурца ===
    private fun createCucumberMask(region: Mat, contour: MatOfPoint): Mat {
        val mask = Mat.zeros(region.size(), CvType.CV_8UC1)
        Imgproc.drawContours(mask, listOf(contour), -1, Scalar(255.0), -1)
        return mask
    }


    private fun skeletonize(mask: Mat): Mat {
        // Преобразуем маску в бинарную (0/1)
        val binary = Mat()
        Imgproc.threshold(mask, binary, 127.0, 1.0, Imgproc.THRESH_BINARY)

        // Применяем Zhang-Suen (реализация ниже)
        val skeletonBinary = skeletonizeZhangSuen(binary)

        // Обратно в 0/255
        val result = Mat()
        Imgproc.threshold(skeletonBinary, result, 0.5, 255.0, Imgproc.THRESH_BINARY)
        return result
    }


    // === НОВАЯ ФУНКЦИЯ: Расчет измерений с использованием скелета ===
    private fun calculateMeasurementsWithSkeleton(
        contour: MatOfPoint,
        skeleton: Mat,
        scale: Float,
        paperRect: Rect
    ): MeasurementResult {
        try {
            // 1. Длина по скелету (криволинейная длина)
            val skeletonLengthPx = calculateSkeletonLength(skeleton)
            val lengthMm = skeletonLengthPx / scale

            // 2. Ширина и диаметр из минимального ограничивающего прямоугольника
            val rect = Imgproc.boundingRect(contour)
            val widthPx = min(rect.width, rect.height).toFloat()
            val diameterMm = widthPx / scale

            // 3. Объем (цилиндр)
            val volumeMm3 = (Math.PI * (diameterMm / 2) * (diameterMm / 2) * lengthMm).toFloat()

            // 4. Дополнительные параметры: кривизна
            val curvature = calculateCurvature(skeleton)

            // 5. Средняя ширина (альтернативный расчет)
            val areaPx = Imgproc.contourArea(contour)
            val avgWidthPx = if (skeletonLengthPx > 0) areaPx / skeletonLengthPx else 0f
            val avgWidthMm = avgWidthPx.toFloat() / scale

            return MeasurementResult(
                length = lengthMm,
                width = avgWidthMm,
                diameter = diameterMm,
                volume = volumeMm3,
                curve = curvature.toFloat()
            )

        } catch (e: Exception) {
            Log.e("ImageProcessor", "Ошибка расчета измерений", e)
            return MeasurementResult(0f, 0f, 0f, 0f, 0f, "Ошибка расчета")
        }
    }

    // === Расчет длины скелета ===
    private fun calculateSkeletonLength(skeleton: Mat): Float {
        val points = mutableListOf<Point>()
        for (y in 0 until skeleton.rows()) {
            for (x in 0 until skeleton.cols()) {
                if (skeleton.get(y, x)[0] == 255.0) {
                    points.add(Point(x.toDouble(), y.toDouble()))
                }
            }
        }

        if (points.size < 2) return points.size.toFloat()

        return Core.countNonZero(skeleton).toFloat() * 0.9f
    }

    // === Расчет кривизны ===
    private fun calculateCurvature(skeleton: Mat): Double {
        val points = MatOfPoint()
        Core.findNonZero(skeleton, points)

        if (points.toList().size < 3) return 0.0

        // Берем несколько точек для оценки кривизны
        val samplePoints = points.toList().filterIndexed { index, _ -> index % 10 == 0 }

        if (samplePoints.size < 3) return 0.0

        var totalCurvature = 0.0
        var count = 0

        for (i in 1 until samplePoints.size - 1) {
            val p1 = samplePoints[i - 1]
            val p2 = samplePoints[i]
            val p3 = samplePoints[i + 1]

            // Вычисляем угол между векторами
            val v1 = Point(p1.x - p2.x, p1.y - p2.y)
            val v2 = Point(p3.x - p2.x, p3.y - p2.y)

            val dot = v1.x * v2.x + v1.y * v2.y
            val norm1 = sqrt(v1.x * v1.x + v1.y * v1.y)
            val norm2 = sqrt(v2.x * v2.x + v2.y * v2.y)

            if (norm1 > 0 && norm2 > 0) {
                val cosAngle = dot / (norm1 * norm2)
                val angle = acos(cosAngle.coerceIn(-1.0, 1.0))
                totalCurvature += angle
                count++
            }
        }

        return if (count > 0) totalCurvature / count else 0.0
    }

    // === Создание отладочного Bitmap со скелетом ===
    private fun createDebugBitmapWithSkeleton(
        srcMat: Mat,
        paperRect: Rect,
        cucumberContour: MatOfPoint,
        skeleton: Mat
    ): Bitmap {
        return try {
            // Создаем копию исходного изображения
            val resultMat = Mat()
            srcMat.copyTo(resultMat)

            // Конвертируем в BGR если нужно
            if (resultMat.channels() == 1) {
                Imgproc.cvtColor(resultMat, resultMat, Imgproc.COLOR_GRAY2BGR)
            } else if (resultMat.channels() == 4) {
                Imgproc.cvtColor(resultMat, resultMat, Imgproc.COLOR_BGRA2BGR)
            }

            // Преобразуем контур в глобальные координаты
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

            // Преобразуем скелет в глобальные координаты
            val skeletonPoints = MatOfPoint()
            Core.findNonZero(skeleton, skeletonPoints)

            val globalSkeletonPoints = mutableListOf<Point>()
            for (point in skeletonPoints.toList()) {
                globalSkeletonPoints.add(
                    Point(
                        point.x + paperRect.x,
                        point.y + paperRect.y
                    )
                )
            }

            // Заливаем область огурца
            val fillMask = Mat.zeros(resultMat.size(), CvType.CV_8UC1)
            Imgproc.drawContours(
                fillMask,
                listOf(globalContour),
                -1,
                Scalar(255.0),
                -1
            )

            val greenColor = Scalar(0.0, 255.0, 0.0)
            val transparency = 0.5

            val tempMat = Mat()
            resultMat.copyTo(tempMat)

            val colorFill = Mat(resultMat.size(), resultMat.type(), greenColor)
            colorFill.copyTo(tempMat, fillMask)

            Core.addWeighted(tempMat, transparency, resultMat, 1 - transparency, 0.0, resultMat)

            // Рисуем скелет (красный)
            for (point in globalSkeletonPoints) {
                Imgproc.circle(
                    resultMat,
                    point,
                    2,
                    Scalar(0.0, 0.0, 255.0), // Красный
                    -1
                )
            }

            // Рисуем контур огурца (темно-зеленый)
            Imgproc.drawContours(
                resultMat,
                listOf(globalContour),
                -1,
                Scalar(0.0, 150.0, 0.0),
                2
            )

            // Рисуем прямоугольник листа (синий)
            Imgproc.rectangle(
                resultMat,
                Point(paperRect.x.toDouble(), paperRect.y.toDouble()),
                Point(
                    (paperRect.x + paperRect.width).toDouble(),
                    (paperRect.y + paperRect.height).toDouble()
                ),
                Scalar(255.0, 0.0, 0.0),
                3
            )

            // Конвертируем в Bitmap
            val resultBitmap = createBitmap(resultMat.width(), resultMat.height())
            val rgbaMat = Mat()
            Imgproc.cvtColor(resultMat, rgbaMat, Imgproc.COLOR_BGR2RGBA)
            Utils.matToBitmap(rgbaMat, resultBitmap)

            resultBitmap

        } catch (e: Exception) {
            Log.e("ImageProcessor", "Ошибка создания debug bitmap", e)
            createBitmap(100, 100).apply {
                eraseColor(Color.RED)
            }
        }
    }

    // === Остальные функции остаются без изменений ===
    private fun convertToBinary(image: Mat): Mat {
        val gray = Mat()
        Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY)
        val binary = Mat()
        Imgproc.threshold(gray, binary, 127.0, 255.0, Imgproc.THRESH_BINARY)
        return binary
    }

    private fun detectSheet(image: Mat): Rect {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            image.clone(),
            contours,
            hierarchy,
            Imgproc.RETR_LIST,
            Imgproc.CHAIN_APPROX_SIMPLE
        )

        var bestContour: MatOfPoint? = null
        var maxArea = 0.0

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area > maxArea) {
                maxArea = area
                bestContour = contour
            }
        }

        return bestContour?.let { Imgproc.boundingRect(it) } ?: Rect()
    }

    private fun findCucumberAdaptive(region: Mat): MatOfPoint {
        val colorRanges = listOf(
            Pair(Scalar(35.0, 40.0, 40.0), Scalar(85.0, 255.0, 255.0)),
            Pair(Scalar(20.0, 40.0, 40.0), Scalar(40.0, 255.0, 255.0)),
            Pair(Scalar(60.0, 40.0, 40.0), Scalar(90.0, 255.0, 255.0))
        )

        for ((lower, upper) in colorRanges) {
            val contour = findCucumberByColorRange(region, lower, upper)
            if (!contour.empty()) {
                return contour
            }
        }

        return MatOfPoint()
    }

    private fun findCucumberByColorRange(region: Mat, lower: Scalar, upper: Scalar): MatOfPoint {
        try {
            val hsv = Mat()
            Imgproc.cvtColor(region, hsv, Imgproc.COLOR_BGR2HSV)
            val mask = Mat()
            Core.inRange(hsv, lower, upper, mask)

            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel, Point(-1.0, -1.0), 1)
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel, Point(-1.0, -1.0), 2)

            val contours = mutableListOf<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(
                mask,
                contours,
                hierarchy,
                Imgproc.RETR_EXTERNAL,
                Imgproc.CHAIN_APPROX_SIMPLE
            )

            if (contours.isEmpty()) return MatOfPoint()

            var bestContour: MatOfPoint? = null
            var maxArea = 0.0

            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                if (area < region.width() * region.height() * 0.01) continue

                val rect = Imgproc.boundingRect(contour)
                val aspectRatio = rect.width.toFloat() / rect.height.toFloat()
                val elongation = max(aspectRatio, 1 / aspectRatio)
                if (elongation < 1.3) continue

                if (area > maxArea) {
                    maxArea = area
                    bestContour = contour
                }
            }

            return bestContour ?: MatOfPoint()

        } catch (e: Exception) {
            return MatOfPoint()
        }
    }

    private fun calculateScaleFromRect(rect: Rect): Float {
        val widthPx = rect.width.toFloat()
        val heightPx = rect.height.toFloat()
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

    private fun createSimpleDebugBitmap(srcMat: Mat, paperRect: Rect): Bitmap {
        val resultBitmap = createBitmap(srcMat.width(), srcMat.height())
        val resultMat = Mat()
        srcMat.copyTo(resultMat)

        Imgproc.rectangle(
            resultMat,
            Point(paperRect.x.toDouble(), paperRect.y.toDouble()),
            Point(
                (paperRect.x + paperRect.width).toDouble(),
                (paperRect.y + paperRect.height).toDouble()
            ),
            Scalar(255.0, 0.0, 0.0),
            3
        )

        Utils.matToBitmap(resultMat, resultBitmap)
        return resultBitmap
    }

    /**
     * Скелетизация бинарного изображения по алгоритму Zhang-Suen
     * Вход: бинарная маска (0 и 255), где 255 = объект
     * Выход: скелет (0 и 255)
     */
    fun skeletonizeZhangSuen(input: Mat): Mat {
        // Преобразуем 255 → 1 для удобства вычислений
        val binary = Mat()
        Core.compare(input, Scalar(0.0), binary, Core.CMP_GT) // >0 → 255
        Imgproc.threshold(binary, binary, 127.0, 1.0, Imgproc.THRESH_BINARY) // теперь 0/1

        val rows = binary.rows()
        val cols = binary.cols()
        var current = binary.toByteArray()
        var changed = true

        while (changed) {
            changed = false
            val next = current.clone()

            // Этап 1
            for (i in 1 until rows - 1) {
                for (j in 1 until cols - 1) {
                    if (current[i * cols + j] == 1.toByte()) {
                        val neighbors = get8Neighbors(current, i, j, cols)
                        val sum = neighbors.sumOf { it.toInt() }
                        if (sum in 2..6 && countTransitions(neighbors) == 1) {
                            val p2 = neighbors[0].toInt()
                            val p4 = neighbors[2].toInt()
                            val p6 = neighbors[4].toInt()
                            val p8 = neighbors[6].toInt()
                            if (p2 * p4 * p6 == 0 && p4 * p6 * p8 == 0) {
                                next[i * cols + j] = 0
                                changed = true
                            }
                        }
                    }
                }
            }
            current = next

            // Этап 2
            val next2 = current.clone()
            for (i in 1 until rows - 1) {
                for (j in 1 until cols - 1) {
                    if (current[i * cols + j] == 1.toByte()) {
                        val neighbors = get8Neighbors(current, i, j, cols)
                        val sum = neighbors.sumOf { it.toInt() }
                        if (sum in 2..6 && countTransitions(neighbors) == 1) {
                            val p2 = neighbors[0].toInt()
                            val p4 = neighbors[2].toInt()
                            val p6 = neighbors[4].toInt()
                            val p8 = neighbors[6].toInt()
                            if (p2 * p4 * p8 == 0 && p2 * p6 * p8 == 0) {
                                next2[i * cols + j] = 0
                                changed = true
                            }
                        }
                    }
                }
            }
            current = next2
        }

        // Обратно: 1 → 255
        val result =
            current.map { if (it == 1.toByte()) 255.toByte() else 0.toByte() }.toByteArray()
        return result.toMat(rows, cols)
    }

    // Копия Mat как массив байтов (удобно для обработки)
    fun Mat.toByteArray(): ByteArray {
        val data = ByteArray(this.rows() * this.cols())
        this.get(0, 0, data)
        return data
    }

    fun ByteArray.toMat(rows: Int, cols: Int): Mat {
        val mat = Mat(rows, cols, CvType.CV_8UC1)
        mat.put(0, 0, this)
        return mat
    }

    private fun get8Neighbors(data: ByteArray, i: Int, j: Int, cols: Int): List<Byte> {
        return listOf(
            data[(i - 1) * cols + j],     // p2
            data[(i - 1) * cols + j + 1], // p3
            data[i * cols + j + 1],       // p4
            data[(i + 1) * cols + j + 1], // p5
            data[(i + 1) * cols + j],     // p6
            data[(i + 1) * cols + j - 1], // p7
            data[i * cols + j - 1],       // p8
            data[(i - 1) * cols + j - 1]  // p9 (== p1)
        )
    }

    private fun countTransitions(neighbors: List<Byte>): Int {
        // Считаем переходы 0→1 в последовательности p2,p3,...,p9,p2
        var count = 0
        val seq = neighbors + neighbors[0] // замыкаем цикл
        for (i in 0 until 8) {
            if (seq[i] == 0.toByte() && seq[i + 1] == 1.toByte()) count++
        }
        return count
    }

    fun drawTextOnMat(mat: Mat, text: String, point: Point, textSizeZ: Float, color: Scalar) {
        val bitmap = createBitmap(mat.cols(), mat.rows())
        Utils.matToBitmap(mat, bitmap)

        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            this.color = Color.argb(
                255,
                color.`val`[2].toInt(),
                color.`val`[1].toInt(),
                color.`val`[0].toInt()
            )
            textSize = textSizeZ * context.resources.displayMetrics.scaledDensity
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }

        canvas.drawText(text, point.x.toFloat(), point.y.toFloat(), paint)
        Utils.bitmapToMat(bitmap, mat)
        bitmap.recycle()
    }
}