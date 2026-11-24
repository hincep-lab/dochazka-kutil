package cz.kutil.dochazka.data

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import cz.kutil.dochazka.core.ExportHeader
import cz.kutil.dochazka.core.FormCanvas
import cz.kutil.dochazka.core.FormPainter
import cz.kutil.dochazka.core.MonthSummary
import java.io.OutputStream

/** Výkaz do PDF (A4) – vykreslení stejného formuláře přes Android PdfDocument. Bez internetu, bez knihoven. */
object PdfExporter {
    fun write(month: MonthSummary, header: ExportHeader, out: OutputStream) {
        val doc = PdfDocument()
        try {
            for (p in 0 until FormPainter.pages(month)) {
                val info = PdfDocument.PageInfo.Builder(FormPainter.PAGE_W.toInt(), FormPainter.PAGE_H.toInt(), p + 1).create()
                val page = doc.startPage(info)
                FormPainter.draw(AndroidFormCanvas(page.canvas), p, month, header)
                doc.finishPage(page)
            }
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }
}

private class AndroidFormCanvas(private val c: Canvas) : FormCanvas {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.BLACK; strokeCap = Paint.Cap.SQUARE }
    private val fillPaint = Paint().apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { color = Color.BLACK }

    private fun font(size: Float, bold: Boolean, italic: Boolean): Paint {
        val style = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, style)
        textPaint.textSize = size
        return textPaint
    }

    override fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float) {
        stroke.strokeWidth = width
        c.drawLine(x1, y1, x2, y2, stroke)
    }

    override fun fill(x: Float, y: Float, w: Float, h: Float, gray: Float) {
        val v = (gray * 255).toInt().coerceIn(0, 255)
        fillPaint.color = Color.rgb(v, v, v)
        c.drawRect(x, y, x + w, y + h, fillPaint)
    }

    override fun text(s: String, x: Float, y: Float, size: Float, bold: Boolean, italic: Boolean) {
        c.drawText(s, x, y, font(size, bold, italic))
    }

    override fun width(s: String, size: Float, bold: Boolean, italic: Boolean): Float = font(size, bold, italic).measureText(s)
}
