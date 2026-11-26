package com.local.directprint

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.OpenableColumns
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity() {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.local.directprint.USB_PERMISSION"
    }

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var usbManager: UsbManager
    private var activePrinterInfo: PrinterEndpointInfo? = null
    private var pendingUsbPrintAfterPermission = false

    private var isPdfMode = false
    private var selectedUris: List<Uri> = emptyList()
    private var totalPages = 0
    private var currentPreviewPage = 0
    private var displayName = "Dokumen"

    // Views
    private lateinit var usbStatusDot: View
    private lateinit var tvUsbTitle: TextView
    private lateinit var tvUsbDetail: TextView
    private lateinit var btnScanUsb: MaterialButton
    private lateinit var btnPickDoc: MaterialButton
    private lateinit var btnPickImages: MaterialButton
    private lateinit var tvFileInfo: TextView
    private lateinit var previewContainer: FrameLayout
    private lateinit var ivPreview: ImageView
    private lateinit var previewNavBar: LinearLayout
    private lateinit var btnPrevPage: MaterialButton
    private lateinit var btnNextPage: MaterialButton
    private lateinit var tvPageCounter: TextView
    private lateinit var spPaperSize: Spinner
    private lateinit var spColorMode: Spinner
    private lateinit var spOrientation: Spinner
    private lateinit var etPageRange: EditText
    private lateinit var btnPrintAndroidService: MaterialButton
    private lateinit var btnPrintUsbDirect: MaterialButton
    private lateinit var btnSaveAsPdf: MaterialButton
    private lateinit var tvLog: TextView

    private val pickDocLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            handlePickedDocument(uri)
        }
    }

    private val pickImagesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach {
                try {
                    contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {}
            }
            loadImageDocuments(uris)
        }
    }

    private val savePdfLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { outUri ->
        if (outUri != null) {
            exportCurrentToPdfUri(outUri)
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = getUsbDeviceExtra(intent)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null) {
                        scanUsbPrinters()
                        if (pendingUsbPrintAfterPermission) {
                            pendingUsbPrintAfterPermission = false
                            startDirectUsbPrint()
                        }
                    } else {
                        pendingUsbPrintAfterPermission = false
                        setLog("Izin USB ditolak. Ketuk Izinkan saat pop-up muncul.")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    scanUsbPrinters()
                    setLog("Perangkat USB OTG terhubung.")
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    activePrinterInfo = null
                    scanUsbPrinters()
                    setLog("Kabel USB OTG dilepas.")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        bindViews()
        setupSpinners()
        setupListeners()
        registerUsbReceiver()
        scanUsbPrinters()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        uiScope.cancel()
    }

    private fun bindViews() {
        usbStatusDot = findViewById(R.id.usbStatusDot)
        tvUsbTitle = findViewById(R.id.tvUsbTitle)
        tvUsbDetail = findViewById(R.id.tvUsbDetail)
        btnScanUsb = findViewById(R.id.btnScanUsb)
        btnPickDoc = findViewById(R.id.btnPickDoc)
        btnPickImages = findViewById(R.id.btnPickImages)
        tvFileInfo = findViewById(R.id.tvFileInfo)
        previewContainer = findViewById(R.id.previewContainer)
        ivPreview = findViewById(R.id.ivPreview)
        previewNavBar = findViewById(R.id.previewNavBar)
        btnPrevPage = findViewById(R.id.btnPrevPage)
        btnNextPage = findViewById(R.id.btnNextPage)
        tvPageCounter = findViewById(R.id.tvPageCounter)
        spPaperSize = findViewById(R.id.spPaperSize)
        spColorMode = findViewById(R.id.spColorMode)
        spOrientation = findViewById(R.id.spOrientation)
        etPageRange = findViewById(R.id.etPageRange)
        btnPrintAndroidService = findViewById(R.id.btnPrintAndroidService)
        btnPrintUsbDirect = findViewById(R.id.btnPrintUsbDirect)
        btnSaveAsPdf = findViewById(R.id.btnSaveAsPdf)
        tvLog = findViewById(R.id.tvLog)
    }

    private fun setupSpinners() {
        val papers = listOf("A4 (210 × 297 mm)", "F4 / Folio (215 × 330 mm)", "Letter", "4R Foto (10 × 15 cm)")
        val colors = listOf("Berwarna (Asli)", "Hitam Putih (Monokrom)", "Mode Scan (Kertas Putih Tajam)")
        val orientations = listOf("Portrait (Tegak)", "Landscape (Mendatar)")

        spPaperSize.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, papers)
        spColorMode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, colors)
        spOrientation.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, orientations)
    }

    private fun setupListeners() {
        btnScanUsb.setOnClickListener { scanUsbPrinters(requestPermissionIfNeeded = true) }

        btnPickDoc.setOnClickListener {
            pickDocLauncher.launch(
                arrayOf(
                    "application/pdf",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "text/plain",
                    "text/csv",
                    "text/comma-separated-values"
                )
            )
        }

        btnPickImages.setOnClickListener {
            pickImagesLauncher.launch(arrayOf("image/*"))
        }

        btnPrevPage.setOnClickListener {
            if (currentPreviewPage > 0) {
                currentPreviewPage--
                renderCurrentPreview()
            }
        }

        btnNextPage.setOnClickListener {
            if (currentPreviewPage < totalPages - 1) {
                currentPreviewPage++
                renderCurrentPreview()
            }
        }

        btnPrintAndroidService.setOnClickListener { startAndroidSystemPrint() }

        btnPrintUsbDirect.setOnClickListener {
            val info = activePrinterInfo
            if (info == null) {
                Toast.makeText(this, "Colokkan printer lewat OTG dulu", Toast.LENGTH_SHORT).show()
                scanUsbPrinters(requestPermissionIfNeeded = true)
                return@setOnClickListener
            }
            if (!usbManager.hasPermission(info.device)) {
                pendingUsbPrintAfterPermission = true
                requestUsbPermission(info.device)
                return@setOnClickListener
            }
            startDirectUsbPrint()
        }

        btnSaveAsPdf.setOnClickListener {
            val base = displayName.substringBeforeLast('.').ifBlank { "dokumen" }
            savePdfLauncher.launch("${base}_cetak.pdf")
        }
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    private fun getUsbDeviceExtra(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        val permIntent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = PendingIntent.getBroadcast(this, 0, permIntent, flags)
        usbManager.requestPermission(device, pi)
    }

    private fun scanUsbPrinters(requestPermissionIfNeeded: Boolean = false) {
        val first = UsbPrinterHelper.findPrinters(usbManager).firstOrNull()
        if (first == null) {
            activePrinterInfo = null
            tvUsbTitle.text = "Printer USB OTG belum terdeteksi"
            tvUsbDetail.text = "Colokkan kabel OTG ke printer, pastikan printer menyala."
            setDotColor(ContextCompat.getColor(this, R.color.status_idle))
            updatePrintButtons()
            return
        }

        val info = UsbPrinterHelper.inspectDevice(first)
        activePrinterInfo = info
        tvUsbTitle.text = UsbPrinterHelper.describeDevice(first)

        val protoLabel = when {
            info == null -> "Endpoint cetak tidak ditemukan"
            info.isIppOverUsb -> "Mode IPP-over-USB siap (Cetak langsung didukung)"
            else -> "Mode USB Raw (untuk Canon G2010/klasik gunakan Layanan Cetak Android)"
        }

        val hasPerm = usbManager.hasPermission(first)
        tvUsbDetail.text = if (hasPerm) "$protoLabel • Izin USB aktif"
        else "$protoLabel • Ketuk Cek OTG untuk memberi izin USB"

        setDotColor(ContextCompat.getColor(this, R.color.teal))
        if (!hasPerm && requestPermissionIfNeeded) requestUsbPermission(first)
        updatePrintButtons()
    }

    private fun setDotColor(colorInt: Int) {
        val bg = usbStatusDot.background
        if (bg is GradientDrawable) bg.setColor(colorInt) else usbStatusDot.setBackgroundColor(colorInt)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = getStreamUriExtra(intent) ?: return
                val type = intent.type.orEmpty()
                if (type.startsWith("image/")) loadImageDocuments(listOf(uri))
                else handlePickedDocument(uri)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = getStreamUriListExtra(intent)
                if (uris.isNotEmpty()) loadImageDocuments(uris)
            }
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return
                handlePickedDocument(uri)
            }
        }
    }

    private fun getStreamUriExtra(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    private fun getStreamUriListExtra(intent: Intent): List<Uri> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }
    }

    private fun queryFileName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0)
                if (!name.isNullOrBlank()) return name
            }
        }
        return uri.lastPathSegment ?: "Dokumen"
    }

    private fun handlePickedDocument(uri: Uri) {
        val name = queryFileName(uri)
        val mime = contentResolver.getType(uri).orEmpty()
        if (name.lowercase().endsWith(".pdf") || mime == "application/pdf") {
            loadPdfDocument(uri, name)
            return
        }

        // Convert Office (.docx, .xlsx, .pptx) / CSV / TXT into cached PDF automatically
        setLog("Merender $name ke halaman siap cetak...")
        tvFileInfo.text = "Memproses $name..."
        uiScope.launch {
            val renderedFile = withContext(Dispatchers.IO) {
                try {
                    OfficeDocumentRenderer.renderToTempPdf(this@MainActivity, uri, name)
                } catch (e: Exception) {
                    runOnUiThread { setLog("Gagal membaca $name: ${e.message}") }
                    null
                }
            }
            if (renderedFile != null && renderedFile.exists()) {
                setLog("Berhasil merender $name.")
                loadPdfDocument(Uri.fromFile(renderedFile), name)
            } else {
                Toast.makeText(this@MainActivity, "Format file tidak didukung atau rusak", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadPdfDocument(uri: Uri, nameOverride: String? = null) {
        isPdfMode = true
        selectedUris = listOf(uri)
        currentPreviewPage = 0
        displayName = nameOverride ?: queryFileName(uri)
        etPageRange.setText("")

        uiScope.launch {
            val count = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        PdfRenderer(pfd).use { it.pageCount }
                    } ?: 0
                } catch (_: Exception) { 0 }
            }
            if (count <= 0) {
                Toast.makeText(this@MainActivity, "Gagal membaca dokumen", Toast.LENGTH_SHORT).show()
                return@launch
            }
            totalPages = count
            tvFileInfo.text = "$displayName • $totalPages halaman siap cetak"
            previewContainer.visibility = View.VISIBLE
            previewNavBar.visibility = if (totalPages > 1) View.VISIBLE else View.GONE
            renderCurrentPreview()
            updatePrintButtons()
        }
    }

    private fun loadImageDocuments(uris: List<Uri>) {
        isPdfMode = false
        selectedUris = uris
        totalPages = uris.size
        currentPreviewPage = 0
        displayName = if (uris.size == 1) queryFileName(uris[0]) else "${uris.size}_gambar"
        etPageRange.setText("")

        tvFileInfo.text = if (uris.size == 1) "$displayName • 1 gambar" else "${uris.size} gambar dipilih"
        previewContainer.visibility = View.VISIBLE
        previewNavBar.visibility = if (totalPages > 1) View.VISIBLE else View.GONE
        renderCurrentPreview()
        updatePrintButtons()
    }

    private fun renderCurrentPreview() {
        tvPageCounter.text = "${currentPreviewPage + 1} / $totalPages"
        btnPrevPage.isEnabled = currentPreviewPage > 0
        btnNextPage.isEnabled = currentPreviewPage < totalPages - 1

        val uris = selectedUris
        if (uris.isEmpty()) return

        uiScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                try {
                    if (isPdfMode) {
                        contentResolver.openFileDescriptor(uris[0], "r")?.use { pfd ->
                            PdfRenderer(pfd).use { renderer ->
                                renderer.openPage(currentPreviewPage).use { page ->
                                    val w = (page.width * 1.4f).toInt().coerceIn(300, 1200)
                                    val h = (page.height * 1.4f).toInt().coerceIn(300, 1600)
                                    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                    b.eraseColor(Color.WHITE)
                                    page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    b
                                }
                            }
                        }
                    } else {
                        val uri = uris[currentPreviewPage]
                        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                        var sample = 1
                        while (opts.outWidth / sample > 1200 || opts.outHeight / sample > 1200) sample *= 2
                        contentResolver.openInputStream(uri)?.use {
                            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                        }
                    }
                } catch (_: Exception) { null }
            }
            if (bmp != null) ivPreview.setImageBitmap(bmp)
        }
    }

    private fun updatePrintButtons() {
        val hasDoc = selectedUris.isNotEmpty() && totalPages > 0
        btnPrintAndroidService.isEnabled = hasDoc
        btnSaveAsPdf.isEnabled = hasDoc
        btnPrintUsbDirect.isEnabled = hasDoc && activePrinterInfo != null
    }

    private fun parsePageRange(raw: String, maxPage: Int): List<Int> {
        val clean = raw.trim()
        if (clean.isEmpty()) return (1..maxPage).toList()
        val set = linkedSetOf<Int>()
        for (part in clean.split(",")) {
            val p = part.trim()
            if (p.isEmpty()) continue
            if (p.contains("-")) {
                val bounds = p.split("-")
                val a = bounds.getOrNull(0)?.trim()?.toIntOrNull()
                val b = bounds.getOrNull(1)?.trim()?.toIntOrNull()
                if (a != null && b != null) {
                    for (i in minOf(a, b).coerceAtLeast(1)..maxOf(a, b).coerceAtMost(maxPage)) set.add(i)
                }
            } else {
                val n = p.toIntOrNull()
                if (n != null && n in 1..maxPage) set.add(n)
            }
        }
        return if (set.isEmpty()) (1..maxPage).toList() else set.sorted()
    }

    private fun startAndroidSystemPrint() {
        if (selectedUris.isEmpty()) return
        val pages = parsePageRange(etPageRange.text.toString(), totalPages)
        val colorModeIdx = spColorMode.selectedItemPosition
        val isLandscape = spOrientation.selectedItemPosition == 1

        val baseMedia = when (spPaperSize.selectedItemPosition) {
            1 -> PrintAttributes.MediaSize("ISO_F4", "F4 / Folio", 8464, 12992)
            2 -> PrintAttributes.MediaSize.NA_LETTER
            3 -> PrintAttributes.MediaSize("PHOTO_4R", "4R Foto", 4000, 6000)
            else -> PrintAttributes.MediaSize.ISO_A4
        }
        val mediaSize = if (isLandscape) baseMedia.asLandscape() else baseMedia.asPortrait()

        val attrs = PrintAttributes.Builder()
            .setMediaSize(mediaSize)
            .setColorMode(if (colorModeIdx > 0) PrintAttributes.COLOR_MODE_MONOCHROME else PrintAttributes.COLOR_MODE_COLOR)
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()

        val printManager = getSystemService(Context.PRINT_SERVICE) as PrintManager
        val adapter = DirectPrintAdapter(this, displayName, isPdfMode, selectedUris, pages, colorModeIdx)
        printManager.print(displayName, adapter, attrs)
        setLog("Dialog cetak Android dibuka (${pages.size} halaman).")
    }

    private fun exportCurrentToPdfUri(outUri: Uri) {
        val pages = parsePageRange(etPageRange.text.toString(), totalPages)
        val colorModeIdx = spColorMode.selectedItemPosition
        val isLandscape = spOrientation.selectedItemPosition == 1
        setLog("Menyimpan file PDF...")

        uiScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val (bytes, _) = buildPrintReadyPdf(pages, colorModeIdx, isLandscape)
                    contentResolver.openOutputStream(outUri)?.use { it.write(bytes) }
                    true
                } catch (e: Exception) {
                    runOnUiThread { setLog("Gagal simpan PDF: ${e.message}") }
                    false
                }
            }
            if (ok) {
                setLog("PDF berhasil disimpan (${pages.size} halaman).")
                Toast.makeText(this@MainActivity, "PDF tersimpan", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startDirectUsbPrint() {
        val info = activePrinterInfo ?: return
        val pages = parsePageRange(etPageRange.text.toString(), totalPages)
        val colorModeIdx = spColorMode.selectedItemPosition
        val isLandscape = spOrientation.selectedItemPosition == 1

        btnPrintUsbDirect.isEnabled = false
        setLog("Menyiapkan dokumen untuk USB OTG...")

        uiScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val (payloadBytes, mime) = buildPrintReadyPdf(pages, colorModeIdx, isLandscape)
                    UsbPrinterHelper.sendPrintPayload(usbManager, info, payloadBytes, mime, displayName) { status ->
                        runOnUiThread { setLog(status) }
                    }
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

            result.onSuccess { msg ->
                setLog(msg)
                Toast.makeText(this@MainActivity, "Data terkirim ke USB", Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                setLog("Gagal USB Direct: ${err.message}")
                Toast.makeText(this@MainActivity, "Gagal cetak USB langsung", Toast.LENGTH_LONG).show()
            }
            updatePrintButtons()
        }
    }

    private fun buildPrintReadyPdf(
        pages: List<Int>,
        colorModeIdx: Int,
        isLandscape: Boolean
    ): Pair<ByteArray, String> {
        val isUnmodifiedPdf = isPdfMode && colorModeIdx == 0 && pages.size == totalPages && etPageRange.text.isNullOrBlank()
        if (isUnmodifiedPdf) {
            val raw = contentResolver.openInputStream(selectedUris[0])?.use { it.readBytes() }
                ?: throw IllegalStateException("Gagal membaca PDF.")
            return Pair(raw, "application/pdf")
        }

        val (baseW, baseH) = when (spPaperSize.selectedItemPosition) {
            1 -> Pair(609, 935)
            2 -> Pair(612, 792)
            3 -> Pair(288, 432)
            else -> Pair(595, 842)
        }
        val pageW = if (isLandscape) baseH else baseW
        val pageH = if (isLandscape) baseW else baseH

        val pdf = PdfDocument()
        val paint = DirectPrintAdapter.buildFilterPaint(colorModeIdx)

        try {
            if (isPdfMode) {
                contentResolver.openFileDescriptor(selectedUris[0], "r")?.use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        pages.forEachIndexed { idx, pNum ->
                            val outPage = pdf.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, idx + 1).create())
                            outPage.canvas.drawColor(Color.WHITE)
                            renderer.openPage((pNum - 1).coerceIn(0, renderer.pageCount - 1)).use { src ->
                                val bmpW = (src.width * 2).coerceIn(300, 1800)
                                val bmpH = (src.height * 2).coerceIn(300, 2500)
                                val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                                bmp.eraseColor(Color.WHITE)
                                src.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                drawFit(outPage.canvas, bmp, paint)
                                bmp.recycle()
                            }
                            pdf.finishPage(outPage)
                        }
                    }
                }
            } else {
                pages.forEachIndexed { idx, pNum ->
                    val uri = selectedUris.getOrNull(pNum - 1) ?: return@forEachIndexed
                    val outPage = pdf.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, idx + 1).create())
                    outPage.canvas.drawColor(Color.WHITE)
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                    var sample = 1
                    while (opts.outWidth / sample > 1800 || opts.outHeight / sample > 1800) sample *= 2
                    val bmp = contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                    }
                    if (bmp != null) {
                        drawFit(outPage.canvas, bmp, paint)
                        bmp.recycle()
                    }
                    pdf.finishPage(outPage)
                }
            }

            val baos = ByteArrayOutputStream()
            pdf.writeTo(baos)
            return Pair(baos.toByteArray(), "application/pdf")
        } finally {
            pdf.close()
        }
    }

    private fun drawFit(canvas: Canvas, bmp: Bitmap, paint: Paint) {
        val pad = 16f
        val availW = canvas.width - pad * 2
        val availH = canvas.height - pad * 2
        val scale = minOf(availW / bmp.width.toFloat(), availH / bmp.height.toFloat())
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        canvas.drawBitmap(bmp, null, RectF((canvas.width - dw) / 2f, (canvas.height - dh) / 2f, (canvas.width + dw) / 2f, (canvas.height + dh) / 2f), paint)
    }

    private fun setLog(msg: String) {
        tvLog.text = msg
    }
}
