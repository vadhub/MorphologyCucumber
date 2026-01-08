package com.abg.morphologycucumber

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.abg.morphologycucumber.databinding.ActivityCameraBinding
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Проверяем разрешения
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions()
        }

        setupUI()
    }

    private fun setupUI() {
        binding.btnCapture.setOnClickListener {
            takePhoto()
        }

        binding.btnGallery.setOnClickListener {
            openGallery()
        }

        binding.btnFlipCamera.setOnClickListener {
            flipCamera()
        }

        binding.btnBack.setOnClickListener {
            finish()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .setTargetResolution(Size(1080, 1920))
                .build()
                .also {
                    it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
                }

            // ImageCapture
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetResolution(Size(1080, 1920))
                .build()

            // Выбираем камеру
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            try {
                // Отвязываем все use cases перед привязкой новых
                cameraProvider.unbindAll()

                // Привязываем use cases к камере
                camera = cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageCapture
                )

                // Настраиваем тап-фокус
                setupTapToFocus()

            } catch (exc: Exception) {
                Log.e("CameraActivity", "Use case binding failed", exc)
                Toast.makeText(this, "Не удалось запустить камеру", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupTapToFocus() {
        binding.cameraPreview.setOnTouchListener { v, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN && camera != null) {
                val factory = binding.cameraPreview.meteringPointFactory
                val point = factory.createPoint(event.x, event.y)

                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                    .setAutoCancelDuration(2, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                camera?.cameraControl?.startFocusAndMetering(action)

                // Показываем визуальную обратную связь
                showFocusIndicator(event.x, event.y)
            }
            true
        }
    }

    private fun showFocusIndicator(x: Float, y: Float) {
        binding.focusIndicator.x = x - binding.focusIndicator.width / 2
        binding.focusIndicator.y = y - binding.focusIndicator.height / 2
        binding.focusIndicator.visibility = View.VISIBLE
        binding.focusIndicator.animate()
            .scaleX(1.2f)
            .scaleY(1.2f)
            .alpha(0f)
            .setDuration(500)
            .withEndAction {
                binding.focusIndicator.visibility = View.GONE
                binding.focusIndicator.scaleX = 1f
                binding.focusIndicator.scaleY = 1f
                binding.focusIndicator.alpha = 1f
            }
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        // Создаем временный файл для фото
        val photoFile = createTempFile()

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    // Получаем A4 область для кадрирования
                    val a4Bounds = binding.a4Overlay.getA4BoundsInPreview()

                    // Кадрируем изображение по области A4
                    launchCropImage(photoFile, a4Bounds)

                    showToast("Фото сохранено")
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("CameraActivity", "Photo capture failed: ${exception.message}", exception)
                    showToast("Ошибка при съемке фото")
                }
            }
        )
    }

    private fun createTempFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir = getExternalFilesDir("CucumberPhotos")
        return File.createTempFile(
            "CUCUMBER_${timeStamp}_",
            ".jpg",
            storageDir
        )
    }

    private fun launchCropImage(photoFile: File, a4Bounds: RectF) {
        // Создаем временный файл с метаданными о кадрировании
        val cropInfoFile = File.createTempFile("crop_info", ".txt", cacheDir)
        cropInfoFile.writeText("${a4Bounds.left},${a4Bounds.top},${a4Bounds.right},${a4Bounds.bottom}")

        cropImage.launch(
            CropImageContractOptions(
                uri = Uri.fromFile(photoFile),
                cropImageOptions = CropImageOptions(
                    imageSourceIncludeGallery = false,
                    imageSourceIncludeCamera = false,
                    aspectRatioX = 210,
                    aspectRatioY = 297,
                    maxZoom = 4,
                    outputCompressFormat = Bitmap.CompressFormat.JPEG,
                    outputCompressQuality = 90,
                    guidelines = com.canhub.cropper.CropImageView.Guidelines.ON,
                    fixAspectRatio = true,
                    initialCropWindowRectangle = android.graphics.Rect(
                        (a4Bounds.left * 1000).toInt(),
                        (a4Bounds.top * 1000).toInt(),
                        (a4Bounds.right * 1000).toInt(),
                        (a4Bounds.bottom * 1000).toInt()
                    )
                ),
            )
        )
    }

    private val cropImage = registerForActivityResult(CropImageContract()) { result ->
        if (result.isSuccessful) {
            try {
                val croppedBitmap = result.getBitmap(this)
                if (croppedBitmap != null) {
                    // Передаем результат в MainActivity
                    val intent = Intent(this, MainActivity::class.java).apply {
                        putExtra("camera_captured", true)
                        putExtra("cropped_bitmap", saveBitmapToTempFile(croppedBitmap))
                    }
                    startActivity(intent)
                    finish()
                }
            } catch (e: Exception) {
                Log.e("CameraActivity", "Error processing cropped image", e)
                showToast("Ошибка обработки изображения")
            }
        } else {
            showToast("Кадрирование отменено")
        }
    }

    private fun saveBitmapToTempFile(bitmap: Bitmap): String {
        val tempFile = File.createTempFile("cropped_", ".jpg", cacheDir)
        val outputStream = FileOutputStream(tempFile)
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
        outputStream.flush()
        outputStream.close()
        return tempFile.absolutePath
    }

    private fun openGallery() {
        cropImage.launch(
            CropImageContractOptions(
                uri = null,
                cropImageOptions = CropImageOptions(
                    imageSourceIncludeGallery = true,
                    imageSourceIncludeCamera = false,
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

    private fun flipCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        startCamera()
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                showPermissionDeniedDialog()
            }
        }
    }

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Доступ к камере")
            .setMessage("Для использования камеры необходимо предоставить разрешение")
            .setPositiveButton("Настройки") { _, _ ->
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                )
                intent.data = android.net.Uri.fromParts("package", packageName, null)
                startActivity(intent)
            }
            .setNegativeButton("Отмена") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}