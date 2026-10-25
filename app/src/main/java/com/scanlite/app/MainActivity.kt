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
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class FilterMode {
    ORIGINAL,
    COLOR,
    GRAYSCALE,
    BLACK_WHITE,
    DOCUMENT
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            ScanLiteApp()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScanLiteApp() {
    var pages by remember {
        mutableStateOf<List<Bitmap>>(emptyList())
    }

    var screen by remember {
        mutableStateOf("home")
    }

    var filter by remember {
        mutableStateOf(FilterMode.DOCUMENT)
    }

    var quality by remember {
        mutableStateOf(Quality.BALANCED)
    }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val importLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenMultipleDocuments()
        ) { uris ->

            val loaded = uris.mapNotNull { uri ->
                decodeBitmap(context.contentResolver, uri)
            }

            if (loaded.isEmpty() && uris.isNotEmpty()) {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(
                        "Could not read the selected images"
                    )
                }
            }

            if (loaded.isNotEmpty()) {
                pages = pages + loaded
                screen = "pages"
            }
        }

    val createPdfLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument(
                "application/pdf"
            )
        ) { uri ->

            if (uri == null || pages.isEmpty()) {
                return@rememberLauncherForActivityResult
            }

            coroutineScope.launch {
                runCatching {
                    val outputStream =
                        context.contentResolver.openOutputStream(uri)
                            ?: error("Unable to open the selected destination")

                    outputStream.use { output ->
                        PdfWriter.write(
                            pages = pages,
                            output = output,
                            filter = filter,
                            quality = quality
                        )
                    }

                    sharePdf(context, uri)
                }.onFailure { error ->
                    snackbarHostState.showSnackbar(
                        "Export failed: ${error.message ?: "unknown error"}"
                    )
                }
            }
        }

    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) {
            darkColorScheme()
        } else {
            lightColorScheme()
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "ScanLite",
                            fontWeight = FontWeight.Bold
                        )
                    }
                )
            },
            snackbarHost = {
                SnackbarHost(hostState = snackbarHostState)
            }
        ) { paddingValues ->

            when (screen) {
                "camera" -> {
                    CameraScreen(
                        padding = paddingValues,
                        onCaptured = { bitmap ->
                            pages = pages + bitmap
                            screen = "pages"
                        },
                        onBack = {
                            screen = "home"
                        }
                    )
                }

                "pages" -> {
                    PageManager(
                        padding = paddingValues,
                        pages = pages,
                        filter = filter,
                        quality = quality,
                        onFilter = { selectedFilter ->
                            filter = selectedFilter
                        },
                        onQuality = { selectedQuality ->
                            quality = selectedQuality
                        },
                        onMove = { from, to ->
                            pages = pages.move(from, to)
                        },
                        onDelete = { index ->
                            pages = pages.filterIndexed { currentIndex, _ ->
                                currentIndex != index
                            }
                        },
                        onBack = {
                            screen = "home"
                        },
                        onExport = {
                            createPdfLauncher.launch(
                                FileNaming.pdf(
                                    prefix = "ScanLite",
                                    timestamp = timestamp()
                                )
                            )
                        }
                    )
                }

                else -> {
                    HomeScreen(
                        padding = paddingValues,
                        pages = pages,
                        onScan = {
                            screen = "camera"
                        },
                        onImport = {
                            importLauncher.launch(arrayOf("image/*"))
                        },
                        onPages = {
                            if (pages.isNotEmpty()) {
                                screen = "pages"
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    padding: PaddingValues,
    pages: List<Bitmap>,
    onScan: () -> Unit,
    onImport: () -> Unit,
    onPages: () -> Unit
) {
    Column(
        modifier = Modifier
            .padding(padding)
            .padding(20.dp)
            .fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Your private paperless desk",
            style = MaterialTheme.typography.headlineSmall
        )

        Text(
            text = "Scan, enhance, and export readable PDFs entirely on this device."
        )

        Button(
            onClick = onScan,
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = null
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(text = "Scan document")
        }

        OutlinedButton(
            onClick = onImport,
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PhotoLibrary,
                contentDescription = null
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(text = "Import images")
        }

        if (pages.isNotEmpty()) {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp)
            )

            Text(
                text = "Current draft",
                style = MaterialTheme.typography.titleMedium
            )

            Card(
                onClick = onPages,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = null
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Text(
                        text = "${pages.size} page(s) ready to edit and export"
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No documents yet",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CameraScreen(
    padding: PaddingValues,
    onCaptured: (Bitmap) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var imageCapture by remember {
        mutableStateOf<ImageCapture?>(null)
    }

    var camera by remember {
        mutableStateOf<Camera?>(null)
    }

    var torchEnabled by remember {
        mutableStateOf(false)
    }

    var cameraError by remember {
        mutableStateOf<String?>(null)
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (!granted) {
                cameraError = "Camera permission is required to scan."
            }
        }

    LaunchedEffect(Unit) {
        val permissionGranted =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED

        if (!permissionGranted) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(
        modifier = Modifier
            .padding(padding)
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim)
    ) {
        AndroidView(
            factory = { viewContext ->
                PreviewView(viewContext)
            },
            modifier = Modifier.fillMaxSize()
        ) { previewView ->

            val hasCameraPermission =
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED

            if (hasCameraPermission) {
                val cameraProviderFuture =
                    ProcessCameraProvider.getInstance(context)

                cameraProviderFuture.addListener(
                    {
                        runCatching {
                            val cameraProvider =
                                cameraProviderFuture.get()

                            val preview =
                                Preview.Builder()
                                    .build()
                                    .also { previewUseCase ->
                                        previewUseCase.setSurfaceProvider(
                                            previewView.surfaceProvider
                                        )
                                    }

                            val capture =
                                ImageCapture.Builder()
                                    .setCaptureMode(
                                        ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                                    )
                                    .build()

                            imageCapture = capture

                            cameraProvider.unbindAll()

                            camera =
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    capture
                                )
                        }.onFailure {
                            cameraError = "Unable to start the camera."
                        }
                    },
                    ContextCompat.getMainExecutor(context)
                )
            }
        }

        if (cameraError != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = cameraError.orEmpty(),
                    color = MaterialTheme.colorScheme.onPrimary
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    }
                ) {
                    Text(text = "Open settings")
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            IconButton(
                onClick = {
                    torchEnabled = !torchEnabled
                    camera?.cameraControl?.enableTorch(torchEnabled)
                }
            ) {
                Icon(
                    imageVector = if (torchEnabled) {
                        Icons.Default.FlashOn
                    } else {
                        Icons.Default.FlashOff
                    },
                    contentDescription = "Torch",
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }

        FloatingActionButton(
            onClick = {
                capturePhoto(
                    context = context,
                    capture = imageCapture,
                    onCaptured = onCaptured
                )
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(28.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Camera,
                contentDescription = "Capture"
            )
        }
    }
}

private fun capturePhoto(
    context: Context,
    capture: ImageCapture?,
    onCaptured: (Bitmap) -> Unit
) {
    if (capture == null) {
        return
    }

    val targetFile =
        runCatching {
            File.createTempFile(
                "scan_capture_",
                ".jpg",
                context.cacheDir
            )
        }.getOrNull() ?: return

    val outputOptions =
        ImageCapture.OutputFileOptions.Builder(targetFile).build()

    capture.takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {

            override fun onError(exception: ImageCaptureException) {
                targetFile.delete()
            }

            override fun onImageSaved(
                outputFileResults: ImageCapture.OutputFileResults
            ) {
                runCatching {
                    BitmapFactory.decodeFile(targetFile.absolutePath)
                }.getOrNull()?.let { bitmap ->
                    onCaptured(bitmap)
                }

                targetFile.delete()
            }
        }
    )
}

@Composable
private fun PageManager(
    padding: PaddingValues,
    pages: List<Bitmap>,
    filter: FilterMode,
    quality: Quality,
    onFilter: (FilterMode) -> Unit,
    onQuality: (Quality) -> Unit,
    onMove: (Int, Int) -> Unit,
    onDelete: (Int) -> Unit,
    onBack: () -> Unit,
    onExport: () -> Unit
) {
    Column(
        modifier = Modifier
            .padding(padding)
            .padding(16.dp)
            .fillMaxSize()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back"
                )
            }

            Text(
                text = "Pages (${pages.size})",
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = onExport,
                enabled = pages.isNotEmpty()
            ) {
                Icon(
                    imageVector = Icons.Default.PictureAsPdf,
                    contentDescription = null
                )

                Spacer(modifier = Modifier.width(4.dp))

                Text(text = "Export")
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(
                items = pages,
                key = { index, _ -> index }
            ) { index, bitmap ->

                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            bitmap = ImageProcessor
                                .process(bitmap, filter)
                                .asImageBitmap(),
                            contentDescription = "Page ${index + 1}",
                            modifier = Modifier.size(88.dp, 116.dp)
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "Page ${index + 1}",
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                text = "${bitmap.width} × ${bitmap.height} px"
                            )
                        }

                        IconButton(
                            onClick = {
                                if (index > 0) {
                                    onMove(index, index - 1)
                                }
                            },
                            enabled = index > 0
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowUpward,
                                contentDescription = "Move up"
                            )
                        }

                        IconButton(
                            onClick = {
                                if (index < pages.lastIndex) {
                                    onMove(index, index + 1)
                                }
                            },
                            enabled = index < pages.lastIndex
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowDownward,
                                contentDescription = "Move down"
                            )
                        }

                        IconButton(
                            onClick = {
                                onDelete(index)
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete page"
                            )
                        }
                    }
                }
            }
        }

        Text(
            text = "Filter",
            style = MaterialTheme.typography.titleMedium
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterMode.entries.forEach { mode ->
                FilterChip(
                    selected = filter == mode,
                    onClick = {
                        onFilter(mode)
                    },
                    label = {
                        Text(
                            text = mode.name.replace('_', ' '),
                            maxLines = 1
                        )
                    }
                )
            }
        }

        Text(
            text = "Compression",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 10.dp)
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Quality.entries.forEach { selectedQuality ->
                FilterChip(
                    selected = quality == selectedQuality,
                    onClick = {
                        onQuality(selectedQuality)
                    },
                    label = {
                        Text(text = selectedQuality.name)
                    }
                )
            }
        }
    }
}

private fun List<Bitmap>.move(
    from: Int,
    to: Int
): List<Bitmap> {
    if (from !in indices || to !in indices) {
        return this
    }

    return toMutableList().also { list ->
        val item = list.removeAt(from)
        list.add(to, item)
    }
}

private fun decodeBitmap(
    resolver: ContentResolver,
    uri: Uri
): Bitmap? {
    return runCatching {
        resolver.openInputStream(uri)?.use { inputStream ->
            BitmapFactory.decodeStream(inputStream)
        }
    }.getOrNull()
}

private fun timestamp(): String {
    return SimpleDateFormat(
        "yyyyMMdd-HHmmss",
        Locale.US
    ).format(Date())
}

private fun sharePdf(
    context: Context,
    uri: Uri
) {
    val shareIntent =
        Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    context.startActivity(
        Intent.createChooser(
            shareIntent,
            "Share PDF"
        )
    )
}

private object ImageProcessor {

    fun process(
        source: Bitmap,
        mode: FilterMode
    ): Bitmap {
        val output =
            source.copy(
                Bitmap.Config.ARGB_8888,
                false
            )

        if (mode == FilterMode.ORIGINAL || mode == FilterMode.COLOR) {
            return output
        }

        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val colorMatrix =
            ColorMatrix().apply {
                setSaturation(0f)

                if (mode == FilterMode.DOCUMENT) {
                    setScale(
                        1.15f,
                        1.15f,
                        1.15f,
                        1f
                    )
                }
            }

        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)

        canvas.drawBitmap(
            source,
            0f,
            0f,
            paint
        )

        if (
            mode == FilterMode.BLACK_WHITE ||
            mode == FilterMode.DOCUMENT
        ) {
            for (y in 0 until output.height) {
                for (x in 0 until output.width) {
                    val pixel = output.getPixel(x, y)

                    val gray =
                        (
                            Color.red(pixel) * 0.299f +
                                Color.green(pixel) * 0.587f +
                                Color.blue(pixel) * 0.114f
                            ).toInt()

                    val value =
                        if (gray > 170) {
                            255
                        } else {
                            0
                        }

                    output.setPixel(
                        x,
                        y,
                        Color.rgb(value, value, value)
                    )
                }
            }
        }

        return output
    }
}

private object PdfWriter {

    fun write(
        pages: List<Bitmap>,
        output: OutputStream,
        filter: FilterMode,
        quality: Quality
    ) {
        val document = android.graphics.pdf.PdfDocument()

        try {
            pages.forEachIndexed { index, originalBitmap ->
                val processedBitmap =
                    BitmapSizing.fit(
                        source = ImageProcessor.process(
                            source = originalBitmap,
                            mode = filter
                        ),
                        maxDimension = quality.maxDimension
                    )

                val landscape =
                    processedBitmap.width >= processedBitmap.height

                val pageWidth =
                    if (landscape) {
                        842
                    } else {
                        595
                    }

                val pageHeight =
                    if (landscape) {
                        595
                    } else {
                        842
                    }

                val pageInfo =
                    android.graphics.pdf.PdfDocument.PageInfo
                        .Builder(
                            pageWidth,
                            pageHeight,
                            index + 1
                        )
                        .create()

                val page = document.startPage(pageInfo)

                page.canvas.drawColor(Color.WHITE)

                val scale =
                    minOf(
                        pageWidth.toFloat() / processedBitmap.width.toFloat(),
                        pageHeight.toFloat() / processedBitmap.height.toFloat()
                    )

                val drawWidth =
                    (processedBitmap.width * scale).toInt()

                val drawHeight =
                    (processedBitmap.height * scale).toInt()

                val left =
                    (pageWidth - drawWidth) / 2

                val top =
                    (pageHeight - drawHeight) / 2

                page.canvas.drawBitmap(
                    processedBitmap,
                    null,
                    android.graphics.Rect(
                        left,
                        top,
                        left + drawWidth,
                        top + drawHeight
                    ),
                    Paint(Paint.FILTER_BITMAP_FLAG)
                )

                document.finishPage(page)

                if (processedBitmap !== originalBitmap) {
                    processedBitmap.recycle()
                }
            }

            document.writeTo(output)
        } finally {
            document.close()
        }
    }
}
