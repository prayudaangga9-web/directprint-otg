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
    private val selectedPages: List<Int>, // 1-indexed
    private val monochrome: Boolean
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

        val pageCount = selectedPages.size.coerceAtLeast(1)
        val info = PrintDocumentInfo.Builder(jobName)
            .setContentType(
                if (isPdf) PrintDocumentInfo.CONTENT_TYPE_DOCUMENT
                else PrintDocumentInfo.CONTENT_TYPE_PHOTO
            )
            .setPageCount(pageCount)
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
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (monochrome) {
            val cm = ColorMatrix().apply { setSaturation(0f) }
            paint.colorFilter = ColorMatrixColorFilter(cm)
        }

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
                                val scale = 2.0f
                                val bmpW = (srcPage.width * scale).toInt().coerceIn(200, 2200)
                                val bmpH = (srcPage.height * scale).toInt().coerceIn(200, 3100)
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

            FileOutputStream(destination.fileDescriptor).use { out ->
                pdfDoc.writeTo(out)
            }
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: Exception) {
            callback.onWriteFailed(e.message ?: "Gagal menyusun halaman cetak.")
        } finally {
            pdfDoc.close()
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
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
        var sampleSize = 1
        while (opts.outWidth / sampleSize > maxDim || opts.outHeight / sampleSize > maxDim) {
            sampleSize *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        }
    }
}
