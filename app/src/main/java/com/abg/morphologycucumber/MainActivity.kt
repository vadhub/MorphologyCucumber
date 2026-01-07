package com.abg.morphologycucumber

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.abg.morphologycucumber.databinding.ActivityMainBinding
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var imageProcessor: ImageProcessor

    // Переменные для хранения текущего состояния
    private var currentBitmap: Bitmap? = null
    private var currentPhotoPath: String? = null
    private var currentMeasurements: MeasurementResult? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Инициализация OpenCV
        if (!OpenCVLoader.initDebug()) {
            Log.e("MainActivity", "OpenCV initialization failed!")
            showToast("Ошибка инициализации OpenCV")
        } else {
            Log.d("MainActivity", "OpenCV initialized successfully")
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Инициализация компонентов
        imageProcessor = ImageProcessor()

        setupUI()
    }

    private fun setupUI() {
        // Настройка кнопок и обработчиков

        binding.btnSelectImage.setOnClickListener {
            openImagePicker()
        }

        binding.btnAnalyze.setOnClickListener {
            analyzeImage()
        }

        binding.btnSaveResults.setOnClickListener {
            saveResults()
        }

        binding.btnRetry.setOnClickListener {
            resetAnalysis()
        }

        // Изначально скрываем дополнительные элементы
        binding.progressBar.visibility = View.GONE
        binding.detailedResultLayout.visibility = View.GONE
        binding.btnSaveResults.isEnabled = false
        binding.btnRetry.isEnabled = false
    }

    private val cropImage = registerForActivityResult(CropImageContract()) { result ->
        if (result.isSuccessful) {
            result.getBitmap(this)?.let { bitmap ->
                currentBitmap = bitmap
                currentPhotoPath = result.uriContent?.path

                // Обновление UI
                binding.imageView.setImageBitmap(bitmap)
                binding.imageView.visibility = View.VISIBLE
                binding.imagePlaceholder.visibility = View.GONE

                // Сброс предыдущих результатов
                resetResultsUI()

                // Включаем кнопку анализа
                binding.btnAnalyze.isEnabled = true

                showToast("Изображение успешно загружено")
            }
        } else {
            showToast("Не удалось загрузить изображение")
        }
    }

    private fun openImagePicker() {
        cropImage.launch(
            CropImageContractOptions(
                uri = null,
                cropImageOptions = CropImageOptions(
                    imageSourceIncludeGallery = true,
                    imageSourceIncludeCamera = true,
                    aspectRatioX = 1,
                    aspectRatioY = 1,
                    maxZoom = 4,
                    outputCompressFormat = Bitmap.CompressFormat.JPEG,
                    outputCompressQuality = 90
                ),
            ),
        )
    }

    private fun analyzeImage() {
        val bitmap = currentBitmap
        if (bitmap == null) {
            showToast("Сначала выберите изображение")
            return
        }

        // Блокируем кнопки во время анализа
        binding.btnAnalyze.isEnabled = false
        binding.btnSelectImage.isEnabled = false

        // Показать индикатор загрузки
        binding.progressBar.visibility = View.VISIBLE
        binding.resultText.text = "Анализ изображения..."

        lifecycleScope.launch {
            try {
                // Обработка изображения в фоновом потоке
                val measurements = withContext(Dispatchers.Default) {
                    imageProcessor.processCucumberImage(bitmap)
                }

                currentMeasurements = measurements

                // Обновление UI в основном потоке
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE

                    if (measurements.error != null) {
                        // Показать ошибку
                        binding.resultText.text = "Ошибка: ${measurements.error}"
                        binding.btnAnalyze.isEnabled = true
                        binding.btnSelectImage.isEnabled = true
                        showToast(measurements.error)
                        Log.d("!!!", "Ошибка: ${measurements.error}")
                    } else {
                        // Показать результаты
                        updateResultsUI(measurements)
                        binding.btnAnalyze.isEnabled = false
                        binding.btnSelectImage.isEnabled = true
                        binding.btnSaveResults.isEnabled = true
                        binding.btnRetry.isEnabled = true
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.resultText.text = "Ошибка анализа"
                    binding.btnAnalyze.isEnabled = true
                    binding.btnSelectImage.isEnabled = true
                    showToast("Ошибка анализа изображения: ${e.message}")
                    Log.e("MainActivity", "Error analyzing image", e)
                }
            }
        }
    }

    private fun updateResultsUI(measurements: MeasurementResult) {
        // Обновление текстовых полей с результатами
        binding.tvLengthValue.text = String.format("%.1f мм", measurements.length)
        binding.tvWidthValue.text = String.format("%.1f мм", measurements.width)
        binding.tvDiameterValue.text = String.format("%.1f мм", measurements.diameter)
        binding.tvVolumeValue.text = String.format("%.0f мм³", measurements.volume)

        // Расчет примерного веса (предполагаем плотность 0.9 г/см³)
        val weightGrams = measurements.volume * 0.0009f // переводим мм³ в см³ и умножаем на плотность
        binding.tvWeightValue.text = String.format("%.1f г", weightGrams)

        // Обновляем общий текст результата
        binding.resultText.text = String.format(
            "Огурец: %.1f мм × %.1f мм, объем %.0f мм³",
            measurements.length,
            measurements.diameter,
            measurements.volume
        )

        // Показать детальные результаты
        binding.detailedResultLayout.visibility = View.VISIBLE
    }

    private fun resetResultsUI() {
        binding.resultText.text = "Изображение загружено. Нажмите 'Анализировать'"
        binding.detailedResultLayout.visibility = View.GONE
        binding.tvLengthValue.text = "-"
        binding.tvWidthValue.text = "-"
        binding.tvDiameterValue.text = "-"
        binding.tvVolumeValue.text = "-"
        binding.tvWeightValue.text = "-"
        currentMeasurements = null
        binding.btnSaveResults.isEnabled = false
        binding.btnRetry.isEnabled = false
    }

    private fun resetAnalysis() {
        currentBitmap?.let {
            resetResultsUI()
            binding.btnAnalyze.isEnabled = true
            showToast("Анализ сброшен. Можно провести анализ заново.")
        }
    }

    private fun saveResults() {
        val measurements = currentMeasurements
        if (measurements == null || measurements.error != null) {
            showToast("Нет данных для сохранения")
            return
        }

        // Здесь можно реализовать сохранение в базу данных или файл
        // Например, используя Room или SharedPreferences

        // Временное сохранение в SharedPreferences для примера
        val sharedPref = getSharedPreferences("cucumber_measurements", MODE_PRIVATE)
        with(sharedPref.edit()) {
            putFloat("last_length", measurements.length)
            putFloat("last_width", measurements.width)
            putFloat("last_diameter", measurements.diameter)
            putFloat("last_volume", measurements.volume)
            putString("last_photo_path", currentPhotoPath)
            apply()
        }

        showToast("Результаты сохранены")

        // Можно также экспортировать в CSV или показать диалог сохранения
        binding.btnSaveResults.isEnabled = false
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Освобождение ресурсов
        currentBitmap?.recycle()
    }
}