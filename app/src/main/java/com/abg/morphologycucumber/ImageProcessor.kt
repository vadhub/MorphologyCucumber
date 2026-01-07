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
        private const val CONTOUR_APPROX_EPSILON = 0.02
    }

    fun processCucumberImage(bitmap: Bitmap): MeasurementResult {
        return try {
            Log.d("ImageProcessor", "Начало обработки изображения")

            if (bitmap.isRecycled) {
                return MeasurementResult(0f, 0f, 0f, 0f, "Изображение не доступно")
            }

            // Конвертируем в ARGB_8888 если нужно
            val compatibleBitmap = if (bitmap.config != Bitmap.Config.ARGB_8888) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                bitmap
            }

            if (compatibleBitmap == null) {
                return MeasurementResult(0f, 0f, 0f, 0f, "Не удалось преобразовать изображение")
            }

            // 1. Конвертируем Bitmap в Mat
            val srcMat = Mat()
            Utils.bitmapToMat(compatibleBitmap, srcMat)
            Log.d("ImageProcessor", "Изображение загружено: ${srcMat.width()}x${srcMat.height()}")

            // 2. Детектируем лист A4
            val paperContour = detectPaperContourImproved(srcMat)
            Log.d("ImageProcessor", "Контур листа найден: ${!paperContour.empty()}")

            if (paperContour.empty()) {
                return MeasurementResult(0f, 0f, 0f, 0f, "Не удалось найти лист A4. Убедитесь, что:\n1. Лист полностью виден\n2. Лист на светлом фоне\n3. Нет сильных бликов")
            }

            // 3. Получаем масштаб
            val scale = calculateScaleImproved(paperContour)
            Log.d("ImageProcessor", "Масштаб: $scale пикс/мм")

            if (scale <= 0.5f) {
                return MeasurementResult(0f, 0f, 0f, 0f, "Масштаб слишком мал. Сфотографируйте ближе")
            }

            // 4. Обрезаем изображение до области листа
            val paperRegion = cropToPaper(srcMat, paperContour)

            // 5. Детектируем огурец
            val cucumberContour = detectCucumber(paperRegion)
            Log.d("ImageProcessor", "Контур огурца найден: ${!cucumberContour.empty()}")

            if (cucumberContour.empty()) {
                return MeasurementResult(0f, 0f, 0f, 0f, "Не удалось найти огурец. Убедитесь, что:\n1. Огурец на белом листе\n2. Контрастный фон\n3. Хорошее освещение")
            }

            // 6. Анализируем и вычисляем
            val measurements = calculateMeasurements(cucumberContour, scale)
            measurements

        } catch (e: Exception) {
            Log.e("ImageProcessor", "Ошибка обработки", e)
            MeasurementResult(0f, 0f, 0f, 0f, "Ошибка обработки: ${e.localizedMessage}")
        }
    }

    /**
     * Улучшенный метод детектирования листа A4
     */
    private fun detectPaperContourImproved(srcMat: Mat): MatOfPoint {
        try {
            // 1. Уменьшаем изображение для ускорения обработки
            val scaleFactor = 800.0 / maxOf(srcMat.width(), srcMat.height())
            val newWidth = (srcMat.width() * scaleFactor).toInt()
            val newHeight = (srcMat.height() * scaleFactor).toInt()

            val resized = Mat()
            Imgproc.resize(srcMat, resized, Size(newWidth.toDouble(), newHeight.toDouble()))

            // 2. Конвертируем в градации серого
            val gray = Mat()
            Imgproc.cvtColor(resized, gray, Imgproc.COLOR_BGR2GRAY)

            // 3. Нормализуем яркость
            val normalized = Mat()
            Core.normalize(gray, normalized, 0.0, 255.0, Core.NORM_MINMAX)

            // 4. Размытие для удаления шума
            val blurred = Mat()
            Imgproc.GaussianBlur(normalized, blurred, Size(5.0, 5.0), 0.0)

            // 5. Адаптивный порог для работы при разном освещении
            val threshold = Mat()
            Imgproc.adaptiveThreshold(
                blurred, threshold, 255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY_INV, 11, 2.0
            )

            // 6. Морфологические операции для заполнения разрывов
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            val morphed = Mat()
            Imgproc.morphologyEx(threshold, morphed, Imgproc.MORPH_CLOSE, kernel)

            // 7. Находим контуры
            val contours = mutableListOf<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(morphed, contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            Log.d("ImageProcessor", "Найдено контуров: ${contours.size}")

            if (contours.isEmpty()) {
                return MatOfPoint()
            }

            // 8. Ищем самый большой контур (скорее всего это лист)
            var maxArea = 0.0
            var largestContour: MatOfPoint? = null

            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                if (area > maxArea) {
                    maxArea = area
                    largestContour = contour
                }
            }

            if (largestContour == null) {
                return MatOfPoint()
            }

            val largestContour2f = MatOfPoint2f().apply {
                largestContour.convertTo(this, CvType.CV_32F)
            }
            // 9. Аппроксимируем контур (упрощаем до многоугольника)
            val peri = Imgproc.arcLength(largestContour2f, true)
            val approx = MatOfPoint2f()

            Imgproc.approxPolyDP(largestContour2f, approx, CONTOUR_APPROX_EPSILON * peri, true)

            // 10. Конвертируем обратно
            val result = MatOfPoint()
            approx.convertTo(result, CvType.CV_32S)

            // Масштабируем обратно к исходному размеру
            scaleContour(result, 1.0 / scaleFactor)

            return result

        } catch (e: Exception) {
            Log.e("ImageProcessor", "Ошибка в detectPaperContourImproved", e)
            return MatOfPoint()
        }
    }

    /**
     * Масштабирование контура
     */
    private fun scaleContour(contour: MatOfPoint, scale: Double) {
        val points = contour.toArray()
        for (i in points.indices) {
            points[i].x *= scale
            points[i].y *= scale
        }
        contour.fromArray(*points)
    }

    /**
     * Улучшенный расчет масштаба
     */
    private fun calculateScaleImproved(paperContour: MatOfPoint): Float {
        if (paperContour.empty()) return 1.0f

        // Получаем ограничивающий прямоугольник
        val rect = Imgproc.boundingRect(paperContour)

        // Вычисляем ширину и высоту в пикселях
        val widthPx = rect.width
        val heightPx = rect.height

        // Считаем, что большая сторона соответствует 297мм, меньшая - 210мм
        val isLandscape = widthPx > heightPx

        val actualWidthPx = if (isLandscape) widthPx else heightPx
        val actualHeightPx = if (isLandscape) heightPx else widthPx

        // Масштаб по ширине и высоте
        val scaleX = actualWidthPx / A4_WIDTH_MM
        val scaleY = actualHeightPx / A4_HEIGHT_MM

        // Берем среднее значение
        return ((scaleX + scaleY) / 2).toFloat()
    }

    /**
     * Обрезка изображения до области листа
     */
    private fun cropToPaper(srcMat: Mat, paperContour: MatOfPoint): Mat {
        val rect = Imgproc.boundingRect(paperContour)

        // Добавляем небольшой отступ
        val padding = 10
        val x = max(0, rect.x - padding)
        val y = max(0, rect.y - padding)
        val width = min(srcMat.width() - x, rect.width + padding * 2)
        val height = min(srcMat.height() - y, rect.height + padding * 2)

        val roi = Rect(x, y, width, height)
        return Mat(srcMat, roi)
    }

    /**
     * Детектирование огурца
     */
    private fun detectCucumber(paperRegion: Mat): MatOfPoint {
        try {
            // 1. Конвертируем в градации серого
            val gray = Mat()
            Imgproc.cvtColor(paperRegion, gray, Imgproc.COLOR_BGR2GRAY)

            // 2. Улучшаем контраст с помощью CLAHE
            val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
            val enhanced = Mat()
            clahe.apply(gray, enhanced)

            // 3. Пороговая обработка для выделения темного объекта на светлом фоне
            val threshold = Mat()
            Imgproc.threshold(enhanced, threshold, 0.0, 255.0,
                Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)

            // 4. Морфологические операции для очистки
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
            val cleaned = Mat()
            Imgproc.morphologyEx(threshold, cleaned, Imgproc.MORPH_CLOSE, kernel)
            Imgproc.morphologyEx(cleaned, cleaned, Imgproc.MORPH_OPEN, kernel)

            // 5. Находим контуры
            val contours = mutableListOf<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(cleaned, contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            Log.d("ImageProcessor", "Найдено контуров огурца: ${contours.size}")

            if (contours.isEmpty()) {
                return MatOfPoint()
            }

            // 6. Ищем самый большой контур (это должен быть огурец)
            var maxArea = 0.0
            var bestContour: MatOfPoint? = null

            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                val rect = Imgproc.boundingRect(contour)
                val aspectRatio = rect.width.toDouble() / rect.height.toDouble()

                // Фильтруем слишком маленькие и слишком вытянутые объекты
                if (area > maxArea && area > 500 && aspectRatio < 5.0 && aspectRatio > 0.2) {
                    maxArea = area
                    bestContour = contour
                }
            }

            return bestContour ?: MatOfPoint()

        } catch (e: Exception) {
            Log.e("ImageProcessor", "Ошибка в detectCucumber", e)
            return MatOfPoint()
        }
    }

    /**
     * Расчет всех измерений
     */
    private fun calculateMeasurements(cucumberContour: MatOfPoint, scale: Float): MeasurementResult {
        // 1. Получаем ограничивающий прямоугольник
        val rect = Imgproc.boundingRect(cucumberContour)

        // 2. Получаем повернутый прямоугольник для более точных измерений
        val points2f = MatOfPoint2f()
        cucumberContour.convertTo(points2f, CvType.CV_32F)
        val rotatedRect = Imgproc.minAreaRect(points2f)

        // 3. Определяем длину и ширину
        val size = rotatedRect.size
        val lengthPx = max(size.width, size.height)
        val widthPx = min(size.width, size.height)

        // 4. Преобразуем в миллиметры
        val lengthMm = lengthPx / scale
        val widthMm = widthPx / scale

        // 5. Диаметр считаем как среднюю ширину
        val diameterMm = widthMm

        // 6. Объем (цилиндр)
        val volumeMm3 = calculateVolumeCylinder(lengthMm.toFloat(), diameterMm.toFloat())

        return MeasurementResult(
            length = lengthMm.toFloat(),
            width = widthMm.toFloat(),
            diameter = diameterMm.toFloat(),
            volume = volumeMm3
        )
    }

    /**
     * Расчет объема цилиндра
     */
    private fun calculateVolumeCylinder(length: Float, diameter: Float): Float {
        val radius = diameter / 2
        return (Math.PI * radius * radius * length).toFloat()
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