package com.abg.morphologycucumber

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.toColorInt

class A4OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val darkPaint = Paint().apply {
        color = "#80000000".toColorInt() // Полупрозрачный темный
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }

    private val guidePaint = Paint().apply {
        color = "#80FFFFFF".toColorInt() // Полупрозрачные направляющие
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 36f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    private val a4Rect = RectF()
    private val a4Ratio = 210f / 297f // Соотношение A4 (ширина/высота)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateA4Rect(w, h)
    }

    private fun updateA4Rect(width: Int, height: Int) {
        val screenRatio = width.toFloat() / height.toFloat()

        if (screenRatio > a4Ratio) {
            // Экран шире, отталкиваемся от высоты
            val a4Height = height * 0.75f
            val a4Width = a4Height * a4Ratio
            val left = (width - a4Width) / 2
            val top = (height - a4Height) / 2
            a4Rect.set(left, top, left + a4Width, top + a4Height)
        } else {
            // Экран уже, отталкиваемся от ширины
            val a4Width = width * 0.75f
            val a4Height = a4Width / a4Ratio
            val left = (width - a4Width) / 2
            val top = (height - a4Height) / 2
            a4Rect.set(left, top, left + a4Width, top + a4Height)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Рисуем темный фон вокруг A4
        // Верхняя полоса
        canvas.drawRect(0f, 0f, width.toFloat(), a4Rect.top, darkPaint)
        // Нижняя полоса
        canvas.drawRect(0f, a4Rect.bottom, width.toFloat(), height.toFloat(), darkPaint)
        // Левая полоса
        canvas.drawRect(0f, a4Rect.top, a4Rect.left, a4Rect.bottom, darkPaint)
        // Правая полоса
        canvas.drawRect(a4Rect.right, a4Rect.top, width.toFloat(), a4Rect.bottom, darkPaint)

        // Рисуем белую рамку A4
        canvas.drawRect(a4Rect, borderPaint)

        // Рисуем направляющие (перекрестие в центре)
        val centerX = a4Rect.centerX()
        val centerY = a4Rect.centerY()

        // Вертикальная линия
        canvas.drawLine(centerX, a4Rect.top, centerX, a4Rect.bottom, guidePaint)
        // Горизонтальная линия
        canvas.drawLine(a4Rect.left, centerY, a4Rect.right, centerY, guidePaint)

        // Текст инструкции
        canvas.drawText(
            "Поместите огурец внутрь рамки",
            width.toFloat() / 2,
            a4Rect.top - 50f,
            textPaint
        )
    }

    fun getA4Rect(): RectF = RectF(a4Rect)

    fun getA4BoundsInPreview(): RectF {
        // Возвращает координаты A4 в системе координат preview (0-1)
        return RectF(
            a4Rect.left / width,
            a4Rect.top / height,
            a4Rect.right / width,
            a4Rect.bottom / height
        )
    }
}