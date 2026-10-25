package com.scanlite.app

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class FilterMode { ORIGINAL, COLOR, GRAYSCALE, BLACK_WHITE, DOCUMENT }
private enum class Quality(val jpeg: Int) { SMALL(60), BALANCED(78), HIGH(92) }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ScanLiteApp() }
    }
}

@Composable
private fun ScanLiteApp() {
    var pages by remember { mutableStateOf(listOf<Bitmap>()) }
    var screen by remember { mutableStateOf("home") }
    var filter by remember { mutableStateOf(FilterMode.DOCUMENT) }
    var quality by remember { mutableStateOf(Quality.BALANCED) }
    val context = LocalContext.current
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val loaded = uris.mapNotNull { decodeBitmap(context.contentResolver, it) }
        if (loaded.isNotEmpty()) { pages = pages + loaded; screen = "pages" }
    }
    val createPdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null && pages.isNotEmpty()) {
            context.contentResolver.openOutputStream(uri)?.use { PdfWriter.write(pages.map { ImageProcessor.process(it, filter) }, it, quality) }
        }
    }
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(), typography = Typography()) {
        Scaffold(topBar = { TopAppBar(title = { Text("ScanLite", fontWeight = FontWeight.Bold) }) }) { padding ->
            when (screen) {
                "camera" -> CameraScreen(padding, onCaptured = { pages = pages + it; screen = "pages" }, onBack = { screen = "home" })
                "pages" -> PageManager(padding, pages, filter, quality, onFilter = { filter = it }, onQuality = { quality = it }, onDelete = { pages = pages.toMutableList().also { list -> list.removeAt(it) } }, onBack = { screen = "home" }, onExport = { createPdf.launch("ScanLite-${timestamp()}.pdf") })
                else -> HomeScreen(padding, pages, onScan = { screen = "camera" }, onImport = { importLauncher.launch(arrayOf("image/*")) }, onPages = { if (pages.isNotEmpty()) screen = "pages" })
            }
        }
    }
}

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
    var torch by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) { if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.CAMERA) }
    Box(Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.scrim)) {
        AndroidView(factory = { PreviewView(it) }, modifier = Modifier.fillMaxSize()) { view ->
            val providerFuture = ProcessCameraProvider.getInstance(context)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                imageCapture = capture
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
            }, ContextCompat.getMainExecutor(context))
        }
        Row(Modifier.align(Alignment.TopCenter).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { IconButton(onClick = onBack) { Icon(Icons.Default.Close, "Back", tint = MaterialTheme.colorScheme.onPrimary) }; Spacer(Modifier.weight(1f)); IconButton(onClick = { torch = !torch }) { Icon(if (torch) Icons.Default.FlashOn else Icons.Default.FlashOff, "Torch", tint = MaterialTheme.colorScheme.onPrimary) } }
        FloatingActionButton(onClick = { capturePhoto(context, imageCapture, onCaptured) }, modifier = Modifier.align(Alignment.BottomCenter).padding(28.dp)) { Icon(Icons.Default.Camera, "Capture") }
    }
}

private fun capturePhoto(context: Context, capture: ImageCapture?, onCaptured: (Bitmap) -> Unit) {
    val target = File.createTempFile("scan_capture_", ".jpg", context.cacheDir)
    val options = ImageCapture.OutputFileOptions.Builder(target).build()
    capture?.takePicture(options, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
        override fun onError(exception: ImageCaptureException) { target.delete() }
        override fun onImageSaved(output: ImageCapture.OutputFileResults) { BitmapFactory.decodeFile(target.absolutePath)?.let(onCaptured); target.delete() }
    })
}

@Composable
private fun PageManager(padding: PaddingValues, pages: List<Bitmap>, filter: FilterMode, quality: Quality, onFilter: (FilterMode) -> Unit, onQuality: (Quality) -> Unit, onDelete: (Int) -> Unit, onBack: () -> Unit, onExport: () -> Unit) {
    Column(Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }; Text("Pages (${pages.size})", style = MaterialTheme.typography.titleLarge); Spacer(Modifier.weight(1f)); Button(onClick = onExport, enabled = pages.isNotEmpty()) { Icon(Icons.Default.PictureAsPdf, null); Text("Export") } }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) { itemsIndexed(pages) { index, bitmap -> Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) { Image(ImageProcessor.process(bitmap, filter).asImageBitmap(), "Page ${index + 1}", Modifier.size(88.dp, 116.dp)); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text("Page ${index + 1}", fontWeight = FontWeight.Bold); Text("${bitmap.width} × ${bitmap.height} px") }; IconButton(onClick = { onDelete(index) }) { Icon(Icons.Default.Delete, "Delete page") } } } } }
        Text("Filter", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { FilterMode.values().forEach { mode -> FilterChip(selected = filter == mode, onClick = { onFilter(mode) }, label = { Text(mode.name.replace('_', ' '), maxLines = 1) }) } }
        Text("Compression", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Quality.values().forEach { q -> FilterChip(selected = quality == q, onClick = { onQuality(q) }, label = { Text(q.name) }) } }
    }
}

private fun decodeBitmap(resolver: ContentResolver, uri: Uri): Bitmap? = runCatching { resolver.openInputStream(uri)?.use(BitmapFactory::decodeStream) }.getOrNull()
private fun timestamp() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

private object ImageProcessor {
    fun process(source: Bitmap, mode: FilterMode): Bitmap {
        val output = source.copy(Bitmap.Config.ARGB_8888, false)
        if (mode == FilterMode.ORIGINAL || mode == FilterMode.COLOR) return output
        val canvas = android.graphics.Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val matrix = ColorMatrix()
        matrix.setSaturation(0f)
        if (mode == FilterMode.DOCUMENT) matrix.setScale(1.25f, 1.25f, 1.25f, 1f)
        paint.colorFilter = ColorMatrixColorFilter(matrix)
        canvas.drawBitmap(source, 0f, 0f, paint)
        if (mode == FilterMode.BLACK_WHITE || mode == FilterMode.DOCUMENT) {
            for (y in 0 until output.height) for (x in 0 until output.width) {
                val c = output.getPixel(x, y); val gray = (android.graphics.Color.red(c) * .299 + android.graphics.Color.green(c) * .587 + android.graphics.Color.blue(c) * .114).toInt(); val v = if (gray > 170) 255 else 0; output.setPixel(x, y, android.graphics.Color.rgb(v, v, v))
            }
        }
        return output
    }
}

private object PdfWriter {
    fun write(pages: List<Bitmap>, output: java.io.OutputStream, quality: Quality) {
        val document = android.graphics.pdf.PdfDocument()
        pages.forEachIndexed { index, bitmap ->
            val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(bitmap.width.coerceAtMost(2000), bitmap.height.coerceAtMost(2800), index + 1).create()
            val page = document.startPage(pageInfo); page.canvas.drawColor(android.graphics.Color.WHITE); page.canvas.drawBitmap(bitmap, null, android.graphics.Rect(0, 0, pageInfo.pageWidth, pageInfo.pageHeight), Paint(Paint.FILTER_BITMAP_FLAG)); document.finishPage(page)
        }
        document.writeTo(output); document.close()
    }
}
