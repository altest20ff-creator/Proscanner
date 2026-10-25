package com.scanlite.app

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class FilterMode { ORIGINAL, COLOR, GRAYSCALE, BLACK_WHITE, DOCUMENT }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ScanLiteApp() }
    }
}

@Composable
private fun ScanLiteApp() {
    var pages by remember { mutableStateOf(emptyList<Bitmap>()) }
    var screen by remember { mutableStateOf("home") }
    var filter by remember { mutableStateOf(FilterMode.DOCUMENT) }
    var quality by remember { mutableStateOf(Quality.BALANCED) }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val loaded = uris.mapNotNull { decodeBitmap(context.contentResolver, it) }
        if (loaded.isEmpty() && uris.isNotEmpty()) snackbar.showMessage("Could not read the selected images")
        if (loaded.isNotEmpty()) { pages = pages + loaded; screen = "pages" }
    }
    val createPdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null && pages.isNotEmpty()) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    PdfWriter.write(pages, output, filter, quality)
                } ?: error("Unable to open destination")
                shareUri(context, uri)
            }.onFailure { snackbar.showMessage("Export failed: ${it.message ?: "unknown error"}") }
        }
    }
    MaterialTheme(colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()) {
        Scaffold(
            topBar = { TopAppBar(title = { Text("ScanLite", fontWeight = FontWeight.Bold) }) },
            snackbarHost = { SnackbarHost(snackbar) }
        ) { padding ->
            when (screen) {
                "camera" -> CameraScreen(padding, onCaptured = { pages = pages + it; screen = "pages" }, onBack = { screen = "home" })
                "pages" -> PageManager(padding, pages, filter, quality, onFilter = { filter = it }, onQuality = { quality = it }, onMove = { from, to -> pages = pages.move(from, to) }, onDelete = { index -> pages = pages.filterIndexed { i, _ -> i != index } }, onBack = { screen = "home" }, onExport = { createPdf.launch(FileNaming.pdf("ScanLite", timestamp())) })
                else -> HomeScreen(padding, pages, onScan = { screen = "camera" }, onImport = { importLauncher.launch(arrayOf("image/*")) }, onPages = { if (pages.isNotEmpty()) screen = "pages" })
            }
        }
    }
}

private suspend fun SnackbarHostState.showMessage(message: String) { showSnackbar(message) }

@Composable
private fun HomeScreen(padding: PaddingValues, pages: List<Bitmap>, onScan: () -> Unit, onImport: () -> Unit, onPages: () -> Unit) {
    Column(Modifier.padding(padding).padding(20.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Your private paperless desk", style = MaterialTheme.typography.headlineSmall)
        Text("Scan, enhance, and export readable PDFs entirely on this device.")
        Button(onClick = onScan, modifier = Modifier.fillMaxWidth().height(58.dp)) { Icon(Icons.Default.CameraAlt, null); Spacer(Modifier.width(8.dp)); Text("Scan document") }
        OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth().height(58.dp)) { Icon(Icons.Default.PhotoLibrary, null); Spacer(Modifier.width(8.dp)); Text("Import images") }
        if (pages.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Current draft", style = MaterialTheme.typography.titleMedium)
            Card(onClick = onPages, modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Description, null); Spacer(Modifier.width(12.dp)); Text("${pages.size} page(s) ready to edit and export") } }
        } else {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { Text("No documents yet", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun CameraScreen(padding: PaddingValues, onCaptured: (Bitmap) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> denied = !granted }
    LaunchedEffect(Unit) { if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.CAMERA) }
    Box(Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.scrim)) {
        AndroidView(factory = { PreviewView(it) }, modifier = Modifier.fillMaxSize()) { view ->
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                val providerFuture = ProcessCameraProvider.getInstance(context)
                providerFuture.addListener({
                    runCatching {
                        val provider = providerFuture.get()
                        val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                        val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                        imageCapture = capture
                        provider.unbindAll()
                        camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                    }.onFailure { denied = true }
                }, ContextCompat.getMainExecutor(context))
            }
        }
        if (denied) Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text("Camera permission is required to scan.", color = MaterialTheme.colorScheme.onPrimary); Button(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("Open settings") } }
        Row(Modifier.align(Alignment.TopCenter).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { IconButton(onClick = onBack) { Icon(Icons.Default.Close, "Back", tint = MaterialTheme.colorScheme.onPrimary) }; Spacer(Modifier.weight(1f)); IconButton(onClick = { torch = !torch; camera?.cameraControl?.enableTorch(torch) }) { Icon(if (torch) Icons.Default.FlashOn else Icons.Default.FlashOff, "Torch", tint = MaterialTheme.colorScheme.onPrimary) } }
        FloatingActionButton(onClick = { capturePhoto(context, imageCapture, onCaptured) }, modifier = Modifier.align(Alignment.BottomCenter).padding(28.dp)) { Icon(Icons.Default.Camera, "Capture") }
    }
}

private fun capturePhoto(context: Context, capture: ImageCapture?, onCaptured: (Bitmap) -> Unit) {
    val target = File.createTempFile("scan_capture_", ".jpg", context.cacheDir)
    val options = ImageCapture.OutputFileOptions.Builder(target).build()
    capture?.takePicture(options, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
        override fun onError(exception: ImageCaptureException) { target.delete() }
        override fun onImageSaved(output: ImageCapture.OutputFileResults) { BitmapFactory.decodeFile(target.absolutePath)?.let(onCaptured); target.delete() }
    }) ?: target.delete()
}

@Composable
private fun PageManager(padding: PaddingValues, pages: List<Bitmap>, filter: FilterMode, quality: Quality, onFilter: (FilterMode) -> Unit, onQuality: (Quality) -> Unit, onMove: (Int, Int) -> Unit, onDelete: (Int) -> Unit, onBack: () -> Unit, onExport: () -> Unit) {
    Column(Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }; Text("Pages (${pages.size})", style = MaterialTheme.typography.titleLarge); Spacer(Modifier.weight(1f)); Button(onClick = onExport, enabled = pages.isNotEmpty()) { Icon(Icons.Default.PictureAsPdf, null); Text("Export") } }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) { itemsIndexed(pages) { index, bitmap -> Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) { Image(ImageProcessor.process(bitmap, filter).asImageBitmap(), "Page ${index + 1}", Modifier.size(88.dp, 116.dp)); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text("Page ${index + 1}", fontWeight = FontWeight.Bold); Text("${bitmap.width} × ${bitmap.height} px") }; IconButton(onClick = { if (index > 0) onMove(index, index - 1) }, enabled = index > 0) { Icon(Icons.Default.ArrowUpward, "Move up") }; IconButton(onClick = { if (index < pages.lastIndex) onMove(index, index + 1) }, enabled = index < pages.lastIndex) { Icon(Icons.Default.ArrowDownward, "Move down") }; IconButton(onClick = { onDelete(index) }) { Icon(Icons.Default.Delete, "Delete page") } } } } }
        Text("Filter", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { FilterMode.values().forEach { mode -> FilterChip(selected = filter == mode, onClick = { onFilter(mode) }, label = { Text(mode.name.replace('_', ' '), maxLines = 1) }) } }
        Text("Compression", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Quality.values().forEach { q -> FilterChip(selected = quality == q, onClick = { onQuality(q) }, label = { Text(q.name) }) } }
    }
}

private fun List<Bitmap>.move(from: Int, to: Int): List<Bitmap> = toMutableList().also { list -> list.add(to, list.removeAt(from)) }
private fun decodeBitmap(resolver: ContentResolver, uri: Uri): Bitmap? = runCatching { resolver.openInputStream(uri)?.use(BitmapFactory::decodeStream) }.getOrNull()
private fun timestamp() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
private fun shareUri(context: Context, uri: Uri) { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "Share PDF")) }

private object ImageProcessor {
    fun process(source: Bitmap, mode: FilterMode): Bitmap {
        val output = source.copy(Bitmap.Config.ARGB_8888, false)
        if (mode == FilterMode.ORIGINAL || mode == FilterMode.COLOR) return output
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val matrix = ColorMatrix().apply { setSaturation(0f); if (mode == FilterMode.DOCUMENT) setScale(1.15f, 1.15f, 1.15f, 1f) }
        paint.colorFilter = ColorMatrixColorFilter(matrix)
        canvas.drawBitmap(source, 0f, 0f, paint)
        if (mode == FilterMode.BLACK_WHITE || mode == FilterMode.DOCUMENT) {
            for (y in 0 until output.height) for (x in 0 until output.width) {
                val c = output.getPixel(x, y)
                val gray = (Color.red(c) * .299 + Color.green(c) * .587 + Color.blue(c) * .114).toInt()
                val value = if (gray > 170) 255 else 0
                output.setPixel(x, y, Color.rgb(value, value, value))
            }
        }
        return output
    }
}

private object PdfWriter {
    fun write(pages: List<Bitmap>, output: OutputStream, filter: FilterMode, quality: Quality) {
        val document = android.graphics.pdf.PdfDocument()
        try {
            pages.forEachIndexed { index, original ->
                val processed = BitmapSizing.fit(ImageProcessor.process(original, filter), quality.maxDimension)
                val width = if (processed.width >= processed.height) 842 else 595
                val height = if (processed.width >= processed.height) 595 else 842
                val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(width, height, index + 1).create()
                val page = document.startPage(pageInfo)
                page.canvas.drawColor(Color.WHITE)
                val scale = minOf(width.toFloat() / processed.width, height.toFloat() / processed.height)
                val drawWidth = (processed.width * scale).toInt()
                val drawHeight = (processed.height * scale).toInt()
                val left = (width - drawWidth) / 2
                val top = (height - drawHeight) / 2
                page.canvas.drawBitmap(processed, null, android.graphics.Rect(left, top, left + drawWidth, top + drawHeight), Paint(Paint.FILTER_BITMAP_FLAG))
                document.finishPage(page)
                if (processed !== original) processed.recycle()
            }
            document.writeTo(output)
        } finally { document.close() }
    }
}
