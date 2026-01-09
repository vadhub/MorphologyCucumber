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
                    MeasurementResult(0f, 0f, 0f, 0f,0f, "Изображение не доступно"),
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
                    MeasurementResult(0f, 0f, 0f, 0f, 0f,"Не удалось преобразовать изображение"),
                    null, null, null
                )
            }

            // 1. Конвертируем Bitmap в Mat
            val srcMat = Mat()
            Utils.bitmapToMat(compatibleBitmap, srcMat)
            Log.d("ImageProcessor", "Размер srcMat: ${srcMat.width()}x${srcMat.height()}, каналы: ${srcMat.channels()}")

            // 2. бинаризация
            val binary = convertToBinary(srcMat)

            // 3. Находим лист A4 (новый метод)
            val paperRect = detectSheet(binary)
            Log.d("ImageProcessor", "Найден прямоугольник листа: $paperRect")

            if (paperRect.width <= 0 || paperRect.height <= 0) {
                return ProcessedResult(
                    MeasurementResult(0f, 0f, 0f, 0f, 0f,"Не удалось определить лист A4"),
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
                    MeasurementResult(0f, 0f, 0f, 0f,0f,"Не удалось найти огурец"),
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
                MeasurementResult(0f, 0f, 0f, 0f, 0f,"Ошибка обработки: ${e.localizedMessage}"),
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

    // === НОВАЯ ФУНКЦИЯ: Скелетизация ===
    private fun skeletonize(mask: Mat): Mat {
        val skeleton = Mat.zeros(mask.size(), mask.type())
        val temp = Mat()
        val eroded = Mat()

        val element = Imgproc.getStructuringElement(
            Imgproc.MORPH_CROSS,
            Size(3.0, 3.0)
        )

        var done = false

        // Клонируем маску для работы
        mask.copyTo(temp)

        while (!done) {
            // Эрозия
            Imgproc.erode(temp, eroded, element)
            // Дилатация
            Imgproc.dilate(eroded, temp, element, Point(-1.0, -1.0), 1)
            // Вычитание
            Core.subtract(temp, eroded, temp)
            // Объединение с скелетом
            Core.bitwise_or(skeleton, temp, skeleton)
            // Копируем эродированное изображение
            eroded.copyTo(temp)

            // Проверяем, остались ли белые пиксели
            done = Core.countNonZero(temp) == 0
        }

        return skeleton
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
            return MeasurementResult(0f, 0f, 0f, 0f,0f, "Ошибка расчета")
        }
    }

    // === Расчет длины скелета ===
    private fun calculateSkeletonLength(skeleton: Mat): Float {
        // Находим все точки скелета
        val points = MatOfPoint()
        Core.findNonZero(skeleton, points)

        if (points.empty()) return 0f

        val pointList = points.toList()

        // Если точек мало, используем простое подсчет
        if (pointList.size < 2) {
            return Core.countNonZero(skeleton).toFloat()
        }

        // Ищем конечные точки скелета (точки с 1 соседом)
        val endpoints = findEndpoints(skeleton)

        if (endpoints.size >= 2) {
            // Пытаемся найти самый длинный путь между конечными точками
            return findLongestPathLength(skeleton, endpoints)
        }

        // Альтернатива: используем алгоритм для подсчета длины цепочки
        return calculateChainLength(skeleton)
    }

    // === Поиск конечных точек скелета ===
    private fun findEndpoints(skeleton: Mat): List<Point> {
        val endpoints = mutableListOf<Point>()
        val rows = skeleton.rows()
        val cols = skeleton.cols()

        for (y in 1 until rows - 1) {
            for (x in 1 until cols - 1) {
                if (skeleton.get(y, x)[0] == 255.0) {
                    var neighbors = 0

                    // Проверяем 8-соседей
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            if (y + dy >= 0 && y + dy < rows &&
                                x + dx >= 0 && x + dx < cols) {
                                if (skeleton.get(y + dy, x + dx)[0] == 255.0) {
                                    neighbors++
                                }
                            }
                        }
                    }

                    // Конечная точка имеет только 1 соседа
                    if (neighbors == 1) {
                        endpoints.add(Point(x.toDouble(), y.toDouble()))
                    }
                }
            }
        }

        return endpoints
    }

    // === Поиск самой длинной цепочки ===
    private fun findLongestPathLength(skeleton: Mat, endpoints: List<Point>): Float {
        if (endpoints.size < 2) return 0f

        var maxLength = 0f

        // Для каждой пары конечных точек ищем путь
        for (i in 0 until endpoints.size - 1) {
            for (j in i + 1 until endpoints.size) {
                val pathLength = findPathBetweenPoints(skeleton, endpoints[i], endpoints[j])
                if (pathLength > maxLength) {
                    maxLength = pathLength
                }
            }
        }

        return maxLength
    }

    // === Поиск пути между двумя точками ===
    private fun findPathBetweenPoints(skeleton: Mat, start: Point, end: Point): Float {
        val visited = Mat.zeros(skeleton.size(), CvType.CV_8UC1)
        val queue = mutableListOf<Pair<Point, Float>>()
        queue.add(Pair(start, 0f))

        while (queue.isNotEmpty()) {
            val (current, distance) = queue.removeAt(0)
            val x = current.x.toInt()
            val y = current.y.toInt()

            // Помечаем как посещенную
            visited.put(y, x, 255.0)

            // Если достигли конечной точки
            if (abs(current.x - end.x) < 1 && abs(current.y - end.y) < 1) {
                return distance
            }

            // Проверяем 4-соседей
            val neighbors = listOf(
                Point(x + 1.toDouble(), y.toDouble()), Point(x - 1.toDouble(), y.toDouble()),
                Point(x.toDouble(), y + 1.toDouble()), Point(x.toDouble(), y - 1.toDouble())
            )

            for (neighbor in neighbors) {
                val nx = neighbor.x.toInt()
                val ny = neighbor.y.toInt()

                if (nx >= 0 && nx < skeleton.cols() &&
                    ny >= 0 && ny < skeleton.rows()) {

                    if (skeleton.get(ny, nx)[0] == 255.0 &&
                        visited.get(ny, nx)[0] == 0.0) {

                        val newDistance = distance + 1f
                        queue.add(Pair(neighbor, newDistance))
                    }
                }
            }
        }

        return 0f
    }

    // === Альтернативный расчет длины цепочки ===
    private fun calculateChainLength(skeleton: Mat): Float {
        var length = 0f

        // Используем алгоритм следования по цепочке
        val points = MatOfPoint()
        Core.findNonZero(skeleton, points)

        if (points.empty()) return 0f

        // Создаем копию для отметки посещенных точек
        val visited = Mat.zeros(skeleton.size(), CvType.CV_8UC1)

        // Начинаем с любой точки
        val startPoint = points.toList().firstOrNull() ?: return 0f

        var current = startPoint
        var hasNext = true

        while (hasNext) {
            val x = current.x.toInt()
            val y = current.y.toInt()

            // Отмечаем как посещенную
            visited.put(y, x, 255.0)

            // Ищем следующую точку
            var nextPoint: Point? = null

            // Проверяем 8-соседей
            for (dy in -1..1) {
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue

                    val nx = x + dx
                    val ny = y + dy

                    if (nx >= 0 && nx < skeleton.cols() &&
                        ny >= 0 && ny < skeleton.rows()) {

                        if (skeleton.get(ny, nx)[0] == 255.0 &&
                            visited.get(ny, nx)[0] == 0.0) {

                            // Учитываем диагональное расстояние
                            val dist = if (dx != 0 && dy != 0) sqrt(2.0) else 1.0
                            length += dist.toFloat()
                            nextPoint = Point(nx.toDouble(), ny.toDouble())
                            break
                        }
                    }
                }
                if (nextPoint != null) break
            }

            if (nextPoint != null) {
                current = nextPoint
            } else {
                hasNext = false
            }
        }

        return length
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
                val angle = Math.acos(cosAngle.coerceIn(-1.0, 1.0))
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
        Imgproc.findContours(image.clone(), contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

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
            Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

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