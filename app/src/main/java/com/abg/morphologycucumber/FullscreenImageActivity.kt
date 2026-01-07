package com.abg.morphologycucumber

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File

class FullscreenImageActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvInfo: TextView
    private lateinit var tvFileName: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setupFullscreenMode()

        setContentView(R.layout.activity_fullscreen_image)

        imageView = findViewById(R.id.fullscreenImageView)
        progressBar = findViewById(R.id.fullscreenProgressBar)
        tvInfo = findViewById(R.id.fullscreenInfoText)
        tvFileName = findViewById(R.id.fullscreenFileName)

        // Получаем путь к изображению
        val imagePath = intent.getStringExtra("image_path")
        val isDebug = intent.getBooleanExtra("is_debug", false)

        if (imagePath != null) {
            loadImage(imagePath, isDebug)
        } else {
            showError("Не удалось загрузить изображение")
        }

        // Обработчики нажатий
        imageView.setOnClickListener {
            toggleSystemUI()
        }

        // Кнопка назад
        findViewById<View>(R.id.fullscreenBackButton).setOnClickListener {
            onBackPressed()
        }

        // Кнопка закрытия
        findViewById<View>(R.id.fullscreenCloseButton).setOnClickListener {
            finish()
        }
    }

    private fun setupFullscreenMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        supportActionBar?.hide()
    }

    private fun toggleSystemUI() {
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            if (controller.isAppearanceLightNavigationBars) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    private fun loadImage(imagePath: String, isDebug: Boolean) {
        progressBar.visibility = View.VISIBLE
        tvInfo.text = "Загрузка изображения..."

        GlobalScope.launch(Dispatchers.IO) {
            try {
                val file = File(imagePath)
                if (!file.exists()) {
                    runOnUiThread {
                        showError("Файл изображения не найден")
                    }
                    return@launch
                }

                // Загружаем изображение с оптимизацией
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeFile(imagePath, options)

                // Вычисляем коэффициент масштабирования
                val imageHeight = options.outHeight
                val imageWidth = options.outWidth
                var scale = 1

                // Уменьшаем размер для оптимизации памяти
                while (imageWidth / scale > 4096 || imageHeight / scale > 4096) {
                    scale *= 2
                }

                val loadOptions = BitmapFactory.Options().apply {
                    inSampleSize = scale
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }

                val bitmap = BitmapFactory.decodeFile(imagePath, loadOptions)

                runOnUiThread {
                    if (bitmap != null) {
                        // Отображаем изображение
                        imageView.setImageBitmap(bitmap)

                        // Настраиваем масштабирование
                        imageView.scaleType = ImageView.ScaleType.FIT_CENTER

                        // Показываем информацию
                        tvInfo.text = String.format(
                            "Размер: %d × %d пикселей",
                            bitmap.width,
                            bitmap.height
                        )

                        tvFileName.text = if (isDebug) {
                            "Изображение с разметкой"
                        } else {
                            "Оригинальное изображение"
                        }

                        progressBar.visibility = View.GONE
                    } else {
                        showError("Не удалось декодировать изображение")
                    }
                }

            } catch (e: Exception) {
                runOnUiThread {
                    showError("Ошибка загрузки: ${e.message}")
                }
            }
        }
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        progressBar.visibility = View.GONE
        tvInfo.text = message

        imageView.setImageResource(android.R.drawable.ic_menu_report_image)
        imageView.scaleType = ImageView.ScaleType.CENTER
    }

    override fun onBackPressed() {
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        super.onBackPressed()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onDestroy() {
        super.onDestroy()
        imageView.setImageBitmap(null)
    }
}