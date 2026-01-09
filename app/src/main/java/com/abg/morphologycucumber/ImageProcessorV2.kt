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
                    MeasurementResult(0f, 0f, 0f, 0f, error = "Изображение не доступно"),
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
                    MeasurementResult(0f, 0f, 0f, 0f, error = "Не удалось преобразовать изображение"),
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
                    MeasurementResult(0f, 0f, 0f, 0f, error = "Не удалось определить лист A4"),
                    null, null, null
                )
            }

            // 4. Получаем масштаб
            val scale = calculateScaleFromRect(paperRect)
            Log.d("ImageProcessor", "Масштаб: $scale")

            // 5. Обрезаем до области листа
            val paperRegion = Mat(srcMat, paperRect) // Исходное цветное изображение!

            // 6. Ищем огурец (в исходной области листа)
            // Новый: попробуем сначала найти на белом фоне
            var cucumberContour = findObjectWithEdges(paperRegion)

            if (cucumberContour.empty()) {
                // Если не нашли на белом фоне, используем старый адаптивный метод
                cucumberContour = findCucumberAdaptive(paperRegion)
            }

            if (cucumberContour.empty()) {
                val debugBitmap = createSimpleDebugBitmap(srcMat, paperRect)
                return ProcessedResult(
                    MeasurementResult(0f, 0f, 0f, 0f, error = "Не удалось найти огурец"),
                    null, null, debugBitmap
                )
            }

            // 7. Вычисляем измерения
            val measurements = calculateMeasurements(cucumberContour, scale)

            // 8. Создаем Bitmap с визуализацией
            val debugBitmap = createDebugBitmap(srcMat, paperRect, cucumberContour)

            // 9. Возвращаем результат
            ProcessedResult(measurements, cucumberContour, paperRect, debugBitmap)


        } catch (e: Exception) {
            Log.e("ImageProcessor", "Ошибка обработки", e)
            ProcessedResult(
                MeasurementResult(0f, 0f, 0f, 0f, error = "Ошибка обработки: ${e.localizedMessage}"),
                null, null, null
            )
        }
    }

    /**
     * Альтернативный метод с использованием Canny edge detection
     */
    private fun findObjectWithEdges(region: Mat): MatOfPoint {
        try {
            Log.d("ImageProcessor", "Поиск объекта с помощью Canny edges")

            val gray = Mat()
            val edges = Mat()

            // 1. Преобразование в grayscale
            Imgproc.cvtColor(region, gray, Imgproc.COLOR_BGR2GRAY)

            // 2. Размытие для уменьшения шума
            Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)

            // 3. Детектор границ Canny
            Imgproc.Canny(gray, edges, 50.0, 150.0)

            // 4. Морфологические операции для улучшения контуров
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            Imgproc.dilate(edges, edges, kernel)

            // 5. Находим контуры
            val contours = mutableListOf<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(
                edges, contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
            )

            if (contours.isEmpty()) {
                return MatOfPoint()
            }

            // 6. Выбираем самый большой контур с хорошей формой
            var bestContour: MatOfPoint? = null
            var maxArea = 0.0
            val minArea = region.width() * region.height() * 0.01

            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                if (area < minArea) continue

                // Аппроксимируем контур
                val contour2f = MatOfPoint2f()
                contour2f.fromList(contour.toList().map { Point(it.x.toDouble(), it.y.toDouble()) })

                val approxCurve = MatOfPoint2f()
                val epsilon = 0.02 * Imgproc.arcLength(contour2f, true)
                Imgproc.approxPolyDP(contour2f, approxCurve, epsilon, true)

                // Проверяем форму
                val rect = Imgproc.boundingRect(contour)
                val aspectRatio = rect.width.toFloat() / rect.height.toFloat()
                val elongation = max(aspectRatio, 1 / aspectRatio)

                if (elongation > 1.3 && area > maxArea) {
                    maxArea = area
                    bestContour = contour
                }
            }

            return bestContour ?: MatOfPoint()

        } catch (e: Exception) {
            Log.e("ImageProcessor", "Ошибка в findObjectWithEdges", e)
            return MatOfPoint()
        }
    }

    // === Перевод изображения в черно-белое (бинарное) ===
    private fun convertToBinary(image: Mat): Mat {
        val gray = Mat()
        Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY)

        val binary = Mat()
        Imgproc.threshold(gray, binary, 127.0, 255.0, Imgproc.THRESH_BINARY)
        return binary
    }

    // === Определение границ листа A4 ===
    private fun detectSheet(image: Mat): Rect {
        // Поиск контуров
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(image.clone(), contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

        // Отбор самого большого контура
        var bestContour: MatOfPoint? = null
        var maxArea = 0.0

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area > maxArea) {
                maxArea = area
                bestContour = contour
            }
        }

        if (bestContour == null) return Rect()

        return Imgproc.boundingRect(bestContour)
    }

    /**
     * Поиск огурца с перебором цветовых диапазонов (зелёный → жёлтый → тёмно‑зелёный)
     * @param region Обрезанное цветное изображение (область листа A4)
     * @return Найденный контур огурца или пустой MatOfPoint
     */
    private fun findCucumberAdaptive(region: Mat): MatOfPoint {
        // Список цветовых диапазонов для проверки (добавлены темные диапазоны)
        val colorRanges = listOf(
            // 1. Стандартный зелёный (основной вариант)
            Pair(Scalar(35.0, 40.0, 40.0), Scalar(85.0, 255.0, 255.0)),

            // 2. Жёлтый (недозрелые/светлые огурцы)
            Pair(Scalar(20.0, 40.0, 40.0), Scalar(40.0, 255.0, 255.0)),

            // 3. Тёмно‑зелёный/перезревшие
            Pair(Scalar(60.0, 40.0, 40.0), Scalar(90.0, 255.0, 255.0)),

            // 4. ОЧЕНЬ ТЕМНЫЕ ОГУРЦЫ (добавлено)
            // Низкая яркость (V), но зеленый оттенок
            Pair(Scalar(35.0, 30.0, 10.0), Scalar(85.0, 255.0, 80.0)),

            // 5. СОВСЕМ ТЕМНЫЕ (почти черные, но с зеленым оттенком)
            Pair(Scalar(35.0, 20.0, 5.0), Scalar(85.0, 200.0, 60.0)),

            // 6. Огурцы в тени (низкая насыщенность, низкая яркость)
            Pair(Scalar(30.0, 10.0, 10.0), Scalar(90.0, 100.0, 100.0))
        )

        for ((lower, upper) in colorRanges) {
            Log.d("ImageProcessor", "Проверка диапазона: HSV(${lower.`val`[0]}, ${lower.`val`[1]}, ${lower.`val`[2]}) – (${upper.`val`[0]}, ${upper.`val`[1]}, ${upper.`val`[2]})")

            val contour = findCucumberByColorRange(region, lower, upper)
            if (!contour.empty()) {
                Log.d("ImageProcessor", "Огурец найден в диапазоне $lower – $upper")
                return contour
            }
        }

        Log.w("ImageProcessor", "Огурец не найден ни в одном цветовом диапазоне")

        // Пробуем использовать метод по яркости
        return findDarkCucumberByBrightness(region)
    }

    /**
     * Метод для поиска очень темных огурцов по яркости
     * (используется, когда цветовые методы не работают)
     */
    private fun findDarkCucumberByBrightness(region: Mat): MatOfPoint {
        try {
            Log.d("ImageProcessor", "Поиск темного огурца по яркости")

            // 1. Конвертируем в grayscale
            val gray = Mat()
            Imgproc.cvtColor(region, gray, Imgproc.COLOR_BGR2GRAY)

            // 2. Адаптивная бинаризация для темных объектов
            val binary = Mat()

            // Используем адаптивный порог для лучшей работы при неравномерном освещении
            Imgproc.adaptiveThreshold(
                gray, binary,
                255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY_INV, // ИНВЕРСИЯ: темные объекты -> белые
                15, // Размер блока (должен быть нечетным)
                2.0 // Константа вычитания (меньше = чувствительнее)
            )

            // Альтернатива: глобальный порог с низким значением
            // Imgproc.threshold(gray, binary, 50.0, 255.0, Imgproc.THRESH_BINARY_INV)

            // 3. Улучшаем маску
            val kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_ELLIPSE,
                Size(7.0, 7.0) // Увеличиваем ядро для соединения разрывов
            )

            // Сначала закрытие для соединения близких областей
            Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, kernel)

            // Затем открытие для удаления мелких шумов
            Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_OPEN, kernel)

            // Увеличиваем область (дилатация)
            Imgproc.dilate(binary, binary, kernel)

            // 4. Поиск контуров
            val contours = mutableListOf<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(binary, contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            if (contours.isEmpty()) {
                Log.d("ImageProcessor", "Не найдено контуров по яркости")
                return MatOfPoint()
            }

            // 5. Фильтрация контуров
            var bestContour: MatOfPoint? = null
            var bestScore = -1.0
            val minArea = region.width() * region.height() * 0.005 // 0.5% от площади

            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                if (area < minArea) continue

                val rect = Imgproc.boundingRect(contour)

                // Проверяем форму (огурец должен быть вытянутым)
                val aspectRatio = rect.width.toFloat() / rect.height.toFloat()
                val elongation = max(aspectRatio, 1 / aspectRatio)

                if (elongation < 1.3) continue // Слишком круглый

                // Рассчитываем "огурцеобразность" (score)
                val score = calculateCucumberScore(contour, region)

                if (score > bestScore) {
                    bestScore = score
                    bestContour = contour
                }
            }

            if (bestContour != null) {
                Log.d("ImageProcessor", "Темный огурец найден по яркости, score: $bestScore")
                return bestContour
            }

            return MatOfPoint()

        } catch (e: Exception) {
            Log.e("ImageProcessor", "Ошибка поиска темного огурца", e)
            return MatOfPoint()
        }
    }

    /**
     * Расчет "огурцеобразности" контура
     */
    private fun calculateCucumberScore(contour: MatOfPoint, region: Mat): Double {
        val rect = Imgproc.boundingRect(contour)
        val area = Imgproc.contourArea(contour)

        // 1. Соотношение сторон (чем больше, тем лучше для огурца)
        val aspectRatio = max(rect.width, rect.height).toDouble() /
                min(rect.width, rect.height).toDouble()
        val aspectScore = min(aspectRatio / 8.0, 1.0) // Нормализуем

        // 2. Отношение площади к площади bounding box (заполнение)
        val bboxArea = rect.width * rect.height
        val fillScore = if (bboxArea > 0) area / bboxArea else 0.0

        // 3. Солидность (отношение площади к площади выпуклой оболочки)
        val hull = MatOfInt()
        Imgproc.convexHull(contour, hull)
        val hullPoints = MatOfPoint()
        hullPoints.create(hull.rows(), 1, CvType.CV_32SC2)

        for (i in 0 until hull.rows()) {
            val point = contour.toList()[hull.get(i, 0)[0].toInt()]
            hullPoints.put(i, 0, point.x, point.y)
        }

        val hullArea = Imgproc.contourArea(hullPoints)
        val solidity = if (hullArea > 0) area / hullArea else 0.0

        // 4. Цветовая однородность (для темных огурцов)
        val colorScore = calculateDarknessScore(region, rect)

        // Итоговый score
        return aspectScore * 0.4 + fillScore * 0.2 + solidity * 0.2 + colorScore * 0.2
    }

    /**
     * Оценка темноты области
     */
    private fun calculateDarknessScore(region: Mat, rect: Rect): Double {
        try {
            // Обрезаем область огурца
            val crop = Mat(region, rect)

            // Конвертируем в HSV
            val hsv = Mat()
            Imgproc.cvtColor(crop, hsv, Imgproc.COLOR_BGR2HSV)

            // Разделяем каналы
            val channels = mutableListOf<Mat>()
            Core.split(hsv, channels)

            // V-канал (яркость)
            val valueChannel = channels[2]

            // Средняя яркость (0-255)
            val mean = Core.mean(valueChannel)
            val avgBrightness = mean.`val`[0]

            // Нормализуем: чем темнее, тем выше score
            return 1.0 - (avgBrightness / 255.0)

        } catch (e: Exception) {
            return 0.5
        }
    }

    /**
     * Поиск огурца в заданном HSV‑диапазоне
     * @param region Изображение
     * @param lower Нижний порог HSV
     * @param upper Верхний порог HSV
     * @return Контур или пустой MatOfPoint
     */
    private fun findCucumberByColorRange(region: Mat, lower: Scalar, upper: Scalar): MatOfPoint {
        try {
            val hsv = Mat()
            Imgproc.cvtColor(region, hsv, Imgproc.COLOR_BGR2HSV)


            val mask = Mat()
            Core.inRange(hsv, lower, upper, mask)

            // Морфологические операции
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel, Point(-1.0, -1.0), 1)
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel, Point(-1.0, -1.0), 2)
            Imgproc.dilate(mask, mask, kernel, Point(-1.0, -1.0), 1)

            // Поиск контуров
            val contours = mutableListOf<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            if (contours.isEmpty()) return MatOfPoint()

            // Выбор лучшего контура
            var bestContour: MatOfPoint? = null
            var maxArea = 0.0

            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                if (area < region.width() * region.height() * 0.01) continue  // Минимум 1% площади

                val rect = Imgproc.boundingRect(contour)
                val aspectRatio = rect.width.toFloat() / rect.height.toFloat()
                val elongation = max(aspectRatio, 1 / aspectRatio)
                if (elongation < 1.3) continue  // Слишком круглый

                if (area > maxArea) {
                    maxArea = area
                    bestContour = contour
                }
            }

            return bestContour ?: MatOfPoint()


        } catch (e: Exception) {
            Log.e("ImageProcessor", "Ошибка поиска в диапазоне $lower–$upper", e)
            return MatOfPoint()
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
            drawTextOnMat(
                resultMat,
                text,
                Point(paperRect.x.toDouble(), paperRect.y.toDouble() - 10),
                24f, // размер текста
                Scalar(0.0, 255.0, 255.0)
            )

            // Конвертируем обратно в Bitmap
            val resultBitmap = createBitmap(resultMat.width(), resultMat.height())
            Utils.matToBitmap(resultMat, resultBitmap)

            return resultBitmap

        } catch (e: Exception) {
            Log.e("ImageProcessor", "Ошибка создания простого debug bitmap", e)
            // Возвращаем пустой Bitmap в случае ошибки
            return createBitmap(100, 100).apply {
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

            // Убедимся, что изображение имеет 3 каналы (BGR)
            if (resultMat.channels() == 1) {
                Imgproc.cvtColor(resultMat, resultMat, Imgproc.COLOR_GRAY2BGR)
            } else if (resultMat.channels() == 4) {
                Imgproc.cvtColor(resultMat, resultMat, Imgproc.COLOR_BGRA2BGR)
            }

            Log.d("ImageProcessor", "Каналы resultMat: ${resultMat.channels()}")

            // Готовим контур огурца, чтобы знать его границы
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

            // 1. Сначала заливаем область огурца ПОЛУПРОЗРАЧНЫМ зеленым
            val fillMask = Mat.zeros(resultMat.size(), CvType.CV_8UC1)
            Imgproc.drawContours(
                fillMask,
                listOf(globalContour),
                -1,
                Scalar(255.0),
                -1
            )

            // Создаем полупрозрачный зеленый цвет
            val greenColor = Scalar(0.0, 255.0, 0.0) // Зеленый
            val transparency = 0.5 // 50% непрозрачности

            // Применяем полупрозрачную заливку
            val tempMat = Mat()
            resultMat.copyTo(tempMat)

            val colorFill = Mat(resultMat.size(), resultMat.type(), greenColor)
            colorFill.copyTo(tempMat, fillMask)

            // Смешиваем с оригиналом для прозрачности
            Core.addWeighted(tempMat, transparency, resultMat, 1 - transparency, 0.0, resultMat)

            // 2. Теперь рисуем синий прямоугольник листа (ПОВЕРХ заливки)
            Imgproc.rectangle(
                resultMat,
                Point(paperRect.x.toDouble(), paperRect.y.toDouble()),
                Point(
                    (paperRect.x + paperRect.width).toDouble(),
                    (paperRect.y + paperRect.height).toDouble()
                ),
                Scalar(255.0, 0.0, 0.0), // BGR: синий
                4 // Увеличиваем толщину
            )

            // 3. Рисуем контур огурца (темно-зеленый)
            Imgproc.drawContours(
                resultMat,
                listOf(globalContour),
                -1,
                Scalar(0.0, 150.0, 0.0), // Темно-зеленый
                3
            )

            // 4. Рисуем ограничивающий прямоугольник (красный)
            val boundingRect = Imgproc.boundingRect(globalContour)
            Imgproc.rectangle(
                resultMat,
                boundingRect.tl(),
                boundingRect.br(),
                Scalar(0.0, 0.0, 255.0), // BGR: красный
                3
            )

            // 5. Добавляем текст с размерами (ИЗМЕНЯЕМ ПОЗИЦИЮ)
            val text = "ОГУРЕЦ"
            // Размещаем текст выше прямоугольника, чтобы не перекрывался
            val textY = max(30.0, boundingRect.y.toDouble() - 20.0)
            drawTextOnMat(
                resultMat,
                text,
                Point(boundingRect.x.toDouble(), textY),
                24f, // размер текста
                Scalar(0.0, 255.0, 255.0) // Желтый
            )

            // 6. Добавляем текст для листа A4
            val sheetTextY = max(30.0, paperRect.y.toDouble() - 15.0)
            drawTextOnMat(
                resultMat,
                "ЛИСТ А4",
                Point(paperRect.x.toDouble(), sheetTextY),
                20f,
                Scalar(255.0, 0.0, 0.0) // Синий
            )

            // 7. Конвертируем обратно в Bitmap
            val resultBitmap = createBitmap(resultMat.width(), resultMat.height())
            // Конвертируем BGR в RGBA для Android
            val rgbaMat = Mat()
            Imgproc.cvtColor(resultMat, rgbaMat, Imgproc.COLOR_BGR2RGBA)
            Utils.matToBitmap(rgbaMat, resultBitmap)

            Log.d("ImageProcessor", "Debug bitmap создан успешно")
            resultBitmap

        } catch (e: Exception) {
            Log.e("ImageProcessor", "Ошибка создания debug bitmap", e)
            e.printStackTrace()
            // Возвращаем простой красный Bitmap в случае ошибки
            createBitmap(100, 100).apply {
                eraseColor(Color.RED)
            }
        }
    }

    fun drawTextOnMat(mat: Mat, text: String, point: Point, textSizeZ: Float, color: Scalar) {
        // Создаем Bitmap из Mat
        val bitmap = createBitmap(mat.cols(), mat.rows())
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

}