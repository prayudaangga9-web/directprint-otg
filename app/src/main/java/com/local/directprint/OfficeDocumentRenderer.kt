package com.local.directprint

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

object OfficeDocumentRenderer {

    private const val PAGE_W = 595 // A4 width in pt
    private const val PAGE_H = 842 // A4 height in pt
    private const val MARGIN = 36f

    fun renderToTempPdf(context: Context, uri: Uri, fileName: String): File {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val outFile = File(context.cacheDir, "office_rendered.pdf")
        if (outFile.exists()) outFile.delete()

        when (ext) {
            "docx" -> renderDocxToPdf(context, uri, outFile)
            "xlsx" -> renderXlsxToPdf(context, uri, outFile)
            "pptx" -> renderPptxToPdf(context, uri, outFile)
            "csv" -> renderCsvToPdf(context, uri, outFile)
            else -> renderTxtToPdf(context, uri, outFile)
        }
        return outFile
    }

    private fun readZipEntries(context: Context, uri: Uri): Map<String, ByteArray> {
        val map = linkedMapOf<String, ByteArray>()
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && (entry.name.endsWith(".xml") || entry.name.endsWith(".rels"))) {
                        map[entry.name] = zis.readBytes()
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        return map
    }

    private fun renderTxtToPdf(context: Context, uri: Uri, outFile: File) {
        val text = context.contentResolver.openInputStream(uri)?.use {
            it.readBytes().toString(StandardCharsets.UTF_8)
        } ?: ""
        val paragraphs = text.split("\r?\n".toRegex())
        writeParagraphsToPdf(paragraphs, outFile)
    }

    private fun renderDocxToPdf(context: Context, uri: Uri, outFile: File) {
        val entries = readZipEntries(context, uri)
        val docXml = entries["word/document.xml"]
            ?: throw IllegalStateException("File .docx tidak valid (word/document.xml tidak ditemukan).")

        val paragraphs = mutableListOf<String>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(ByteArrayInputStream(docXml), "UTF-8")

        var event = parser.eventType
        val currentPara = StringBuilder()
        var inParagraph = false

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "p" -> {
                            inParagraph = true
                            currentPara.setLength(0)
                        }
                        "t" -> {
                            if (inParagraph) {
                                currentPara.append(parser.nextText())
                            }
                        }
                        "tab" -> if (inParagraph) currentPara.append("    ")
                        "br" -> if (inParagraph) currentPara.append("
")
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "p" && inParagraph) {
                        paragraphs.add(currentPara.toString())
                        inParagraph = false
                    }
                }
            }
            event = parser.next()
        }

        if (paragraphs.isEmpty()) paragraphs.add("(Dokumen Word kosong)")
        writeParagraphsToPdf(paragraphs, outFile)
    }

    private fun renderCsvToPdf(context: Context, uri: Uri, outFile: File) {
        val text = context.contentResolver.openInputStream(uri)?.use {
            it.readBytes().toString(StandardCharsets.UTF_8)
        } ?: ""
        val rows = text.split("\r?\n".toRegex())
            .filter { it.isNotBlank() }
            .map { line -> line.split(",").map { it.trim().removeSurrounding(""") } }
        writeTableToPdf("CSV", rows, outFile)
    }

    private fun renderXlsxToPdf(context: Context, uri: Uri, outFile: File) {
        val entries = readZipEntries(context, uri)

        // Parse sharedStrings.xml
        val sharedStrings = mutableListOf<String>()
        entries["xl/sharedStrings.xml"]?.let { ssBytes ->
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(ByteArrayInputStream(ssBytes), "UTF-8")
            var event = parser.eventType
            var inSi = false
            val sb = StringBuilder()
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "si") { inSi = true; sb.setLength(0) }
                        else if (parser.name == "t" && inSi) sb.append(parser.nextText())
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "si" && inSi) {
                            sharedStrings.add(sb.toString())
                            inSi = false
                        }
                    }
                }
                event = parser.next()
            }
        }

        val sheetKey = entries.keys
            .filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
            .sorted()
            .firstOrNull()
            ?: throw IllegalStateException("Sheet Excel tidak ditemukan di dalam .xlsx")

        val sheetBytes = entries[sheetKey]!!
        val rows = mutableListOf<List<String>>()

        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(ByteArrayInputStream(sheetBytes), "UTF-8")

        var event = parser.eventType
        var currentRow = mutableListOf<String>()
        var cellType = ""
        var cellVal = ""
        var inCell = false

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "row" -> currentRow = mutableListOf()
                        "c" -> {
                            inCell = true
                            cellType = parser.getAttributeValue(null, "t") ?: ""
                            cellVal = ""
                        }
                        "v" -> if (inCell) cellVal = parser.nextText()
                        "t" -> if (inCell) cellVal += parser.nextText()
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "c" -> {
                            val resolved = if (cellType == "s") {
                                val idx = cellVal.toIntOrNull()
                                if (idx != null && idx in sharedStrings.indices) sharedStrings[idx] else cellVal
                            } else {
                                cellVal
                            }
                            currentRow.add(resolved)
                            inCell = false
                        }
                        "row" -> {
                            if (currentRow.any { it.isNotBlank() }) {
                                rows.add(currentRow)
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }

        if (rows.isEmpty()) rows.add(listOf("(Sheet Excel kosong)"))
        writeTableToPdf("Excel Sheet", rows, outFile)
    }

    private fun renderPptxToPdf(context: Context, uri: Uri, outFile: File) {
        val entries = readZipEntries(context, uri)
        val slideKeys = entries.keys
            .filter { it.matches(Regex("ppt/slides/slide\d+\.xml")) }
            .sortedBy { Regex("\d+").find(it)?.value?.toIntOrNull() ?: 0 }

        if (slideKeys.isEmpty()) throw IllegalStateException("Slide PowerPoint tidak ditemukan.")

        val slideW = 842 // 16:9-ish landscape
        val slideH = 595
        val pdf = PdfDocument()

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(20, 20, 19)
            textSize = 22f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(45, 45, 50)
            textSize = 14f
            typeface = Typeface.SANS_SERIF
        }
        val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GRAY
            textSize = 11f
        }

        try {
            slideKeys.forEachIndexed { idx, key ->
                val slideBytes = entries[key] ?: return@forEachIndexed
                val paras = extractPptxSlideTexts(slideBytes)
                val pageInfo = PdfDocument.PageInfo.Builder(slideW, slideH, idx + 1).create()
                val page = pdf.startPage(pageInfo)
                val canvas = page.canvas
                canvas.drawColor(Color.WHITE)

                var y = 56f
                val maxW = slideW - 96f
                paras.forEachIndexed { pIdx, text ->
                    val paint = if (pIdx == 0) titlePaint else bodyPaint
                    val lh = if (pIdx == 0) 30f else 22f
                    val prefix = if (pIdx > 0) "• " else ""
                    for (line in wrapText(paint, prefix + text, maxW)) {
                        if (y > slideH - 44f) break
                        canvas.drawText(line, 48f, y, paint)
                        y += lh
                    }
                    y += if (pIdx == 0) 14f else 6f
                }

                canvas.drawText("Slide ${idx + 1} / ${slideKeys.size}", slideW - 95f, slideH - 22f, numPaint)
                pdf.finishPage(page)
            }

            FileOutputStream(outFile).use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
    }

    private fun extractPptxSlideTexts(slideBytes: ByteArray): List<String> {
        val result = mutableListOf<String>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(ByteArrayInputStream(slideBytes), "UTF-8")

        var event = parser.eventType
        val sb = StringBuilder()
        var inP = false
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "p") { inP = true; sb.setLength(0) }
                    else if (parser.name == "t" && inP) sb.append(parser.nextText())
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "p" && inP) {
                        val trimmed = sb.toString().trim()
                        if (trimmed.isNotEmpty()) result.add(trimmed)
                        inP = false
                    }
                }
            }
            event = parser.next()
        }
        return if (result.isEmpty()) listOf("(Slide tanpa teks)") else result
    }

    private fun writeParagraphsToPdf(paragraphs: List<String>, outFile: File) {
        val pdf = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(24, 24, 27)
            textSize = 11.5f
            typeface = Typeface.SANS_SERIF
        }
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GRAY
            textSize = 9.5f
        }

        val maxW = PAGE_W - MARGIN * 2
        val lh = 16.5f
        var pageNum = 1
        var page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum).create())
        var canvas = page.canvas
        canvas.drawColor(Color.WHITE)
        var y = MARGIN + 12f

        try {
            for (para in paragraphs) {
                val lines = if (para.isBlank()) listOf("") else wrapText(paint, para, maxW)
                for (line in lines) {
                    if (y > PAGE_H - MARGIN - 12f) {
                        canvas.drawText("$pageNum", PAGE_W / 2f, PAGE_H - 18f, footerPaint)
                        pdf.finishPage(page)
                        pageNum++
                        page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum).create())
                        canvas = page.canvas
                        canvas.drawColor(Color.WHITE)
                        y = MARGIN + 12f
                    }
                    if (line.isNotEmpty()) {
                        canvas.drawText(line, MARGIN, y, paint)
                    }
                    y += lh
                }
                y += 4f
            }
            canvas.drawText("$pageNum", PAGE_W / 2f, PAGE_H - 18f, footerPaint)
            pdf.finishPage(page)
            FileOutputStream(outFile).use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
    }

    private fun writeTableToPdf(title: String, rows: List<List<String>>, outFile: File) {
        val maxCols = (rows.maxOfOrNull { it.size } ?: 1).coerceIn(1, 14)
        val isLand = maxCols > 6
        val pw = if (isLand) PAGE_H else PAGE_W
        val ph = if (isLand) PAGE_W else PAGE_H

        val pdf = PdfDocument()
        val headBg = Paint().apply { color = Color.rgb(24, 24, 27) }
        val zebraBg = Paint().apply { color = Color.rgb(248, 248, 246) }
        val borderPaint = Paint().apply {
            color = Color.rgb(212, 212, 206)
            style = Paint.Style.STROKE
            strokeWidth = 0.8f
        }
        val headText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 9.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val cellText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(24, 24, 27)
            textSize = 9f
        }
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(20, 20, 19)
            textSize = 12f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }

        val colW = (pw - MARGIN * 2) / maxCols
        val rowH = 20f
        var pageNum = 1
        var page = pdf.startPage(PdfDocument.PageInfo.Builder(pw, ph, pageNum).create())
        var canvas = page.canvas
        canvas.drawColor(Color.WHITE)
        canvas.drawText(title, MARGIN, MARGIN + 10f, titlePaint)
        var y = MARGIN + 24f

        try {
            rows.forEachIndexed { rIdx, row ->
                if (y + rowH > ph - MARGIN) {
                    pdf.finishPage(page)
                    pageNum++
                    page = pdf.startPage(PdfDocument.PageInfo.Builder(pw, ph, pageNum).create())
                    canvas = page.canvas
                    canvas.drawColor(Color.WHITE)
                    y = MARGIN
                }

                for (c in 0 until maxCols) {
                    val x = MARGIN + c * colW
                    if (rIdx == 0) canvas.drawRect(x, y, x + colW, y + rowH, headBg)
                    else if (rIdx % 2 == 1) canvas.drawRect(x, y, x + colW, y + rowH, zebraBg)
                    canvas.drawRect(x, y, x + colW, y + rowH, borderPaint)

                    val txt = row.getOrNull(c).orEmpty().replace("
", " ")
                    val p = if (rIdx == 0) headText else cellText
                    val clipped = clipText(p, txt, colW - 8f)
                    canvas.drawText(clipped, x + 4f, y + 13.5f, p)
                }
                y += rowH
            }
            pdf.finishPage(page)
            FileOutputStream(outFile).use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
    }

    private fun clipText(paint: Paint, text: String, maxW: Float): String {
        if (paint.measureText(text) <= maxW) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > maxW) end--
        return text.substring(0, end) + "…"
    }

    private fun wrapText(paint: Paint, text: String, maxW: Float): List<String> {
        val out = mutableListOf<String>()
        for (rawLine in text.split("
")) {
            val words = rawLine.split(" ")
            var current = ""
            for (w in words) {
                val test = if (current.isEmpty()) w else "$current $w"
                if (paint.measureText(test) <= maxW) {
                    current = test
                } else {
                    if (current.isNotEmpty()) out.add(current)
                    current = w
                }
            }
            if (current.isNotEmpty() || rawLine.isEmpty()) out.add(current)
        }
        return out
    }
}
