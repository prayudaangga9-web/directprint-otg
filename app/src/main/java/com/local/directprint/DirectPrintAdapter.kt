package com.local.directprint

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.pdf.PrintedPdfDocument
import java.io.FileOutputStream

class DirectPrintAdapter(
    private val context: Context,
    private val jobName: String,
    private val isPdf: Boolean,
    private val uris: List<Uri>,
    private val selectedPages: List<Int>,
    private val colorFilterMode: Int // 0=Color, 1=Monochrome, 2=Scan High-Contrast
) : PrintDocumentAdapter() {

    private var currentAttributes: PrintAttributes? = null

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: Bundle?
    ) {
        currentAttributes = newAttributes
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled()
            return
        }

        val info = PrintDocumentInfo.Builder(jobName)
            .setContentType(if (isPdf) PrintDocumentInfo.CONTENT_TYPE_DOCUMENT else PrintDocumentInfo.CONTENT_TYPE_PHOTO)
            .setPageCount(selectedPages.size.coerceAtLeast(1))
            .build()

        callback.onLayoutFinished(info, true)
    }

    override fun onWrite(
        pages: Array<out PageRange>?,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback
    ) {
        val attrs = currentAttributes ?: PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()

        val pdfDoc = PrintedPdfDocument(context, attrs)
        val paint = buildFilterPaint(colorFilterMode)

        try {
            if (isPdf && uris.isNotEmpty()) {
                val pfd = context.contentResolver.openFileDescriptor(uris[0], "r")
                    ?: throw IllegalStateException("Tidak dapat membuka file PDF.")
                pfd.use { fd ->
                    PdfRenderer(fd).use { renderer ->
                        selectedPages.forEachIndexed { outIdx, pageNum1Based ->
                            if (cancellationSignal?.isCanceled == true) {
                                callback.onWriteCancelled()
                                return
                            }
                            val idx0 = (pageNum1Based - 1).coerceIn(0, renderer.pageCount - 1)
                            val page = pdfDoc.startPage(outIdx)
                            val canvas = page.canvas
                            canvas.drawColor(Color.WHITE)

                            renderer.openPage(idx0).use { srcPage ->
                                val bmpW = (srcPage.width * 2).coerceIn(200, 2200)
                                val bmpH = (srcPage.height * 2).coerceIn(200, 3100)
                                val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                                bitmap.eraseColor(Color.WHITE)
                                srcPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                drawBitmapFit(canvas, bitmap, paint)
                                bitmap.recycle()
                            }
                            pdfDoc.finishPage(page)
                        }
                    }
                }
            } else {
                selectedPages.forEachIndexed { outIdx, pageNum1Based ->
                    if (cancellationSignal?.isCanceled == true) {
                        callback.onWriteCancelled()
                        return
                    }
                    val uri = uris.getOrNull(pageNum1Based - 1) ?: return@forEachIndexed
                    val page = pdfDoc.startPage(outIdx)
                    val canvas = page.canvas
                    canvas.drawColor(Color.WHITE)

                    val bitmap = decodeScaledBitmap(uri, 2200)
                    if (bitmap != null) {
                        drawBitmapFit(canvas, bitmap, paint)
                        bitmap.recycle()
                    }
                    pdfDoc.finishPage(page)
                }
            }

            FileOutputStream(destination.fileDescriptor).use { pdfDoc.writeTo(it) }
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: Exception) {
            callback.onWriteFailed(e.message ?: "Gagal menyusun halaman cetak.")
        } finally {
            pdfDoc.close()
        }
    }

    companion object {
        fun buildFilterPaint(mode: Int): Paint {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            when (mode) {
                1 -> {
                    val cm = ColorMatrix().apply { setSaturation(0f) }
                    paint.colorFilter = ColorMatrixColorFilter(cm)
                }
                2 -> {
                    // Scan mode: grayscale + contrast boost for crisp white paper
                    val contrast = 1.65f
                    val translate = (-0.5f * contrast + 0.5f) * 255f + 22f
                    val cm = ColorMatrix().apply { setSaturation(0f) }
                    val contrastMatrix = ColorMatrix(
                        floatArrayOf(
                            contrast, 0f, 0f, 0f, translate,
                            0f, contrast, 0f, 0f, translate,
                            0f, 0f, contrast, 0f, translate,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                    cm.postConcat(contrastMatrix)
                    paint.colorFilter = ColorMatrixColorFilter(cm)
                }
            }
            return paint
        }
    }

    private fun drawBitmapFit(canvas: Canvas, bitmap: Bitmap, paint: Paint) {
        val margin = 12f
        val availW = (canvas.width - margin * 2).coerceAtLeast(10f)
        val availH = (canvas.height - margin * 2).coerceAtLeast(10f)
        val scale = minOf(availW / bitmap.width.toFloat(), availH / bitmap.height.toFloat())
        val dw = bitmap.width * scale
        val dh = bitmap.height * scale
        val left = (canvas.width - dw) / 2f
        val top = (canvas.height - dh) / 2f
        canvas.drawBitmap(bitmap, null, RectF(left, top, left + dw, top + dh), paint)
    }

    private fun decodeScaledBitmap(uri: Uri, maxDim: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        var sampleSize = 1
        while (opts.outWidth / sampleSize > maxDim || opts.outHeight / sampleSize > maxDim) sampleSize *= 2
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
    }
}
