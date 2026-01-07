package com.abg.morphologycucumber

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
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
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var imageProcessor: ImageProcessor

    private var currentOriginalBitmap: Bitmap? = null
    private var currentDebugBitmap: Bitmap? = null
    private var currentPhotoUri: Uri? = null
    private var showDebugView = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Инициализация OpenCV
        if (!OpenCVLoader.initDebug()) {
            Log.e("MainActivity", "OpenCV initialization failed!")
            binding.resultText.text = "Ошибка: OpenCV не загружен"
        } else {
            Log.d("MainActivity", "OpenCV initialized successfully")
            imageProcessor = ImageProcessor(this)
        }

        setupUI()
    }

    private fun setupUI() {
        binding.btnSelectImage.setOnClickListener {
            openImagePicker()
        }

        binding.btnAnalyze.setOnClickListener {
            analyzeImage()
        }

        binding.btnToggleView.setOnClickListener {
            toggleDebugView()
        }

        binding.btnRetry.setOnClickListener {
            resetAnalysis()
        }

        // Обработчик нажатия на область изображения
        binding.imageHolder.setOnClickListener {
            openFullscreenImage()
        }

        // Начальное состояние
        updateUIState(false, false)
        binding.btnToggleView.visibility = View.GONE
        binding.tvViewMode.visibility = View.GONE
    }

    private fun openFullscreenImage() {
        // Получаем текущее изображение (оригинал или отладочное)
        val bitmap = if (showDebugView && currentDebugBitmap != null) {
            currentDebugBitmap
        } else {
            currentOriginalBitmap
        }

        if (bitmap == null) {
            Toast.makeText(this, "Нет изображения для просмотра", Toast.LENGTH_SHORT).show()
            return
        }

        // Создаем временный файл для передачи изображения
        val tempFile = createTempImageFile(bitmap)
        if (tempFile != null) {
            val intent = Intent(this, FullscreenImageActivity::class.java).apply {
                putExtra("image_path", tempFile.absolutePath)
                putExtra("is_debug", showDebugView && currentDebugBitmap != null)
            }
            startActivity(intent)
        } else {
            Toast.makeText(this, "Не удалось открыть изображение", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createTempImageFile(bitmap: Bitmap): File? {
        return try {
            // Создаем временный файл в кэше
            val cacheDir = cacheDir
            val tempFile = File.createTempFile("temp_image_", ".jpg", cacheDir)

            // Сохраняем Bitmap в файл
            val outputStream = FileOutputStream(tempFile)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
            outputStream.flush()
            outputStream.close()

            tempFile
        } catch (e: Exception) {
            Log.e("MainActivity", "Ошибка создания временного файла", e)
            null
        }
    }

    private val cropImage = registerForActivityResult(CropImageContract()) { result ->
        if (result.isSuccessful) {
            try {
                val bitmap = result.getBitmap(this)
                if (bitmap != null) {
                    // Создаем копию в правильном формате
                    val compatibleBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)

                    if (compatibleBitmap != null) {
                        // Освобождаем старые битмапы
                        currentOriginalBitmap?.recycle()
                        currentDebugBitmap?.recycle()

                        currentOriginalBitmap = compatibleBitmap
                        currentPhotoUri = result.uriContent
                        currentDebugBitmap = null
                        showDebugView = false

                        // Показываем исходное изображение
                        binding.imageView.setImageBitmap(compatibleBitmap)
                        binding.imageView.visibility = View.VISIBLE
                        binding.imagePlaceholder.visibility = View.GONE

                        resetResultsUI()
                        binding.btnAnalyze.isEnabled = true
                        binding.btnToggleView.visibility = View.GONE
                        binding.tvViewMode.visibility = View.GONE

                        showToast("Изображение загружено")
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Ошибка загрузки изображения", e)
                showToast("Ошибка загрузки изображения")
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
                    aspectRatioX = 210,
                    aspectRatioY = 297,
                    maxZoom = 4,
                    outputCompressFormat = Bitmap.CompressFormat.JPEG,
                    outputCompressQuality = 90,
                    guidelines = com.canhub.cropper.CropImageView.Guidelines.ON,
                    fixAspectRatio = true
                ),
            )
        )
    }

    private fun analyzeImage() {
        val bitmap = currentOriginalBitmap
        if (bitmap == null) {
            showToast("Сначала выберите изображение")
            return
        }

        if (bitmap.isRecycled) {
            showToast("Изображение повреждено. Выберите другое.")
            return
        }

        // Обновляем UI
        updateUIState(true, false)
        binding.resultText.text = "Анализ изображения..."

        lifecycleScope.launch {
            try {
                Log.d("MainActivity", "Начало анализа изображения")

                val result = withContext(Dispatchers.IO) {
                    imageProcessor.processCucumberImage(bitmap)
                }

                Log.d("MainActivity", "Анализ завершен: ${result.measurements.error ?: "Успешно"}")

                runOnUiThread {
                    binding.progressBar.visibility = View.GONE

                    if (result.measurements.error != null) {
                        // Ошибка
                        binding.resultText.text = result.measurements.error
                        updateUIState(false, false)
                        showToast("Ошибка анализа")

                        // Если есть debug bitmap, показываем его
                        if (result.debugBitmap != null) {
                            currentDebugBitmap?.recycle()
                            currentDebugBitmap = result.debugBitmap
                            binding.btnToggleView.visibility = View.VISIBLE
                            toggleDebugView()
                        }
                    } else {
                        // Успех
                        displayResults(result)
                        updateUIState(false, true)
                        showToast("Анализ завершен успешно")

                        // Сохраняем debug bitmap и показываем кнопку переключения
                        currentDebugBitmap?.recycle()
                        currentDebugBitmap = result.debugBitmap
                        binding.btnToggleView.visibility = View.VISIBLE
                        toggleDebugView()
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error analyzing image", e)
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.resultText.text = "Ошибка анализа"
                    binding.btnAnalyze.isEnabled = true
                    binding.btnSelectImage.isEnabled = true
                    showToast("Ошибка анализа изображения: ${e.message}")
                }
            }
        }
    }

    private fun displayResults(result: ProcessedResult) {
        // Основной текст с результатами
        val measurements = result.measurements
        binding.resultText.text = String.format(
            "Огурец: %.1f × %.1f мм\nОбъем: %.0f мм³",
            measurements.length,
            measurements.diameter,
            measurements.volume
        )

        // Детальные результаты
        binding.tvLengthValue.text = String.format("%.1f мм", measurements.length)
        binding.tvWidthValue.text = String.format("%.1f мм", measurements.width)
        binding.tvDiameterValue.text = String.format("%.1f мм", measurements.diameter)
        binding.tvVolumeValue.text = String.format("%.0f мм³", measurements.volume)

        // Примерный вес
        val weightGrams = measurements.volume * 0.0009f
        binding.tvWeightValue.text = String.format("%.1f г", weightGrams)

        binding.detailedResultLayout.visibility = View.VISIBLE
    }

    private fun toggleDebugView() {
        showDebugView = !showDebugView

        if (showDebugView && currentDebugBitmap != null) {
            // Показываем изображение с разметкой
            binding.imageView.setImageBitmap(currentDebugBitmap)
            binding.btnToggleView.text = "Показать оригинал"
            binding.tvViewMode.text = "Режим: Разметка"
            binding.tvViewMode.visibility = View.VISIBLE
        } else {
            // Показываем оригинальное изображение
            currentOriginalBitmap?.let {
                binding.imageView.setImageBitmap(it)
            }
            binding.btnToggleView.text = "Показать разметку"
            binding.tvViewMode.text = "Режим: Оригинал"
            binding.tvViewMode.visibility = View.VISIBLE
        }
    }

    private fun resetResultsUI() {
        binding.resultText.text = "Изображение загружено. Нажмите 'Анализировать'"
        binding.detailedResultLayout.visibility = View.GONE
        binding.tvLengthValue.text = "-"
        binding.tvWidthValue.text = "-"
        binding.tvDiameterValue.text = "-"
        binding.tvVolumeValue.text = "-"
        binding.tvWeightValue.text = "-"
        binding.tvViewMode.visibility = View.GONE
    }

    private fun resetAnalysis() {
        resetResultsUI()
        binding.btnAnalyze.isEnabled = true
        binding.btnToggleView.visibility = View.GONE

        // Восстанавливаем оригинальное изображение
        currentOriginalBitmap?.let {
            binding.imageView.setImageBitmap(it)
        }
        showDebugView = false

        showToast("Готово к новому анализу")
    }

    private fun updateUIState(isAnalyzing: Boolean, hasResults: Boolean) {
        binding.progressBar.visibility = if (isAnalyzing) View.VISIBLE else View.GONE
        binding.btnAnalyze.isEnabled = !isAnalyzing && !hasResults
        binding.btnSelectImage.isEnabled = !isAnalyzing
        binding.btnRetry.isEnabled = hasResults
        binding.btnToggleView.isEnabled = hasResults || currentDebugBitmap != null
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Освобождение ресурсов
        currentOriginalBitmap?.recycle()
        currentDebugBitmap?.recycle()
    }
}