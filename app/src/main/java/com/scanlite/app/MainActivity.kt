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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.DisposableEffect
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

    var currentScreen by remember {
        mutableStateOf("home")
    }

    var selectedFilter by remember {
        mutableStateOf(FilterMode.DOCUMENT)
    }

    var selectedQuality by remember {
        mutableStateOf(Quality.BALANCED)
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember {
        SnackbarHostState()
    }

    val importLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenMultipleDocuments()
        ) { uris ->

            if (uris.isEmpty()) {
                return@rememberLauncherForActivityResult
            }

            val imported = uris.mapNotNull { uri ->
                decodeBitmapSafely(
                    resolver = context.contentResolver,
                    uri = uri,
                    maxDimension = Quality.BALANCED.maxDimension
                )
            }

            if (imported.isEmpty()) {
                scope.launch {
                    snackbarHostState.showSnackbar(
                        "The selected files could not be read as images."
                    )
                }
                return@rememberLauncherForActivityResult
            }

            pages = pages + imported
            currentScreen = "pages"
        }

    val createPdfLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument(
                "application/pdf"
            )
        ) { destinationUri ->

            if (destinationUri == null || pages.isEmpty()) {
                return@rememberLauncherForActivityResult
            }

            scope.launch {
                runCatching {
                    val output =
                        context.contentResolver.openOutputStream(
                            destinationUri
                        ) ?: error("Could not open the selected destination.")

                    output.use { stream ->
                        PdfWriter.write(
                            pages = pages,
                            output = stream,
                            filter = selectedFilter,
                            quality = selectedQuality
                        )
                    }

                    sharePdf(
                        context = context,
                        uri = destinationUri
                    )
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
                SnackbarHost(
                    hostState = snackbarHostState
                )
            }
        ) { paddingValues ->

            when (currentScreen) {
                "camera" -> {
                    CameraScreen(
                        padding = paddingValues,
                        onCaptured = { bitmap ->
                            pages = pages + bitmap
                            currentScreen = "pages"
                        },
                        onBack = {
                            currentScreen = "home"
                        }
                    )
                }

                "pages" -> {
                    PageManager(
                        padding = paddingValues,
                        pages = pages,
                        filter = selectedFilter,
                        quality = selectedQuality,
                        onFilterChanged = {
                            selectedFilter = it
                        },
                        onQualityChanged = {
                            selectedQuality = it
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
                            currentScreen = "home"
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
                            currentScreen = "camera"
                        },
                        onImport = {
                            importLauncher.launch(
                                arrayOf("image/*")
                            )
                        },
                        onOpenPages = {
                            if (pages.isNotEmpty()) {
                                currentScreen = "pages"
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
    onOpenPages: () -> Unit
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
            text = "Scan, enhance, and export PDFs on this device."
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

            Spacer(
                modifier = Modifier.width(8.dp)
            )

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

            Spacer(
                modifier = Modifier.width(8.dp)
            )

            Text(text = "Import images")
        }

        if (pages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No documents yet",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            HorizontalDivider()

            Text(
                text = "Current draft",
                style = MaterialTheme.typography.titleMedium
            )

            Card(
                onClick = onOpenPages,
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

                    Spacer(
                        modifier = Modifier.width(12.dp)
                    )

                    Text(
                        text = "${pages.size} page(s) ready"
                    )
                }
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

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

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

    val previewView = remember {
        PreviewView(context)
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { granted ->
            hasPermission = granted

            if (!granted) {
                cameraError = "Camera permission is required."
            }
        }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(
                Manifest.permission.CAMERA
            )
        }
    }

    DisposableEffect(
        hasPermission,
        lifecycleOwner
    ) {
        var disposed = false

        if (hasPermission) {
            val providerFuture =
                ProcessCameraProvider.getInstance(context)

            providerFuture.addListener(
                {
                    if (disposed) {
                        return@addListener
                    }

                    runCatching {
                        val provider = providerFuture.get()

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

                        provider.unbindAll()

                        val boundCamera =
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                capture
                            )

                        if (!disposed) {
                            imageCapture = capture
                            camera = boundCamera
                            cameraError = null
                        }
                    }.onFailure {
                        if (!disposed) {
                            imageCapture = null
                            camera = null
                            cameraError = "Unable to start the camera."
                        }
                    }
                },
                ContextCompat.getMainExecutor(context)
            )
        }

        onDispose {
            disposed = true

            runCatching {
                ProcessCameraProvider
                    .getInstance(context)
                    .get()
                    .unbindAll()
            }

            imageCapture = null
            camera = null
        }
    }

    Box(
        modifier = Modifier
            .padding(padding)
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim)
    ) {
        if (hasPermission) {
            AndroidView(
                factory = {
                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (!hasPermission || cameraError != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = cameraError
                        ?: "Camera permission is required.",
                    color = MaterialTheme.colorScheme.onPrimary
                )

                Spacer(
                    modifier = Modifier.height(12.dp)
                )

                Button(
                    onClick = {
                        if (!hasPermission) {
                            permissionLauncher.launch(
                                Manifest.permission.CAMERA
                            )
                        } else {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse(
                                        "package:${context.packageName}"
                                    )
                                )
                            )
                        }
                    }
                ) {
                    Text(
                        text = if (hasPermission) {
                            "Open settings"
                        } else {
                            "Allow camera"
                        }
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(
                onClick = onBack
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }

            IconButton(
                onClick = {
                    torchEnabled = !torchEnabled
                    camera?.cameraControl?.enableTorch(
                        torchEnabled
                    )
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
                imageCapture?.let { capture ->
                    capturePhoto(
                        context = context,
                        capture = capture,
                        onCaptured = onCaptured
                    )
                }
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
    capture: ImageCapture,
    onCaptured: (Bitmap) -> Unit
) {
    val targetFile =
        runCatching {
            File.createTempFile(
                "scan_capture_",
                ".jpg",
                context.cacheDir
            )
        }.getOrNull() ?: return

    val outputOptions =
        ImageCapture.OutputFileOptions.Builder(
            targetFile
        ).build()

    capture.takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {

            override fun onError(
                exception: ImageCaptureException
            ) {
                targetFile.delete()
            }

            override fun onImageSaved(
                outputFileResults: ImageCapture.OutputFileResults
            ) {
                try {
                    val bitmap =
                        decodeFileSafely(
                            file = targetFile,
                            maxDimension = Quality.BALANCED.maxDimension
                        )

                    if (bitmap != null && !bitmap.isRecycled) {
                        onCaptured(bitmap)
                    }
                } finally {
                    targetFile.delete()
                }
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
    onFilterChanged: (FilterMode) -> Unit,
    onQualityChanged: (Quality) -> Unit,
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
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back"
                    )
                }

                Text(
                    text = "Pages (${pages.size})",
                    style = MaterialTheme.typography.titleLarge
                )
            }

            Button(
                onClick = onExport,
                enabled = pages.isNotEmpty()
            ) {
                Icon(
                    imageVector = Icons.Default.PictureAsPdf,
                    contentDescription = null
                )

                Spacer(
                    modifier = Modifier.width(4.dp)
                )

                Text(text = "Export")
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 430.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(
                items = pages
            ) { index, bitmap ->

                val previewBitmap = remember(
                    bitmap,
                    filter
                ) {
                    createPreviewBitmap(
                        source = bitmap,
                        filter = filter
                    )
                }

                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            bitmap = previewBitmap.asImageBitmap(),
                            contentDescription = "Page ${index + 1}",
                            modifier = Modifier.size(72.dp, 96.dp)
                        )

                        Spacer(
                            modifier = Modifier.width(8.dp)
                        )

                        Column(
                            modifier = Modifier.width(92.dp)
                        ) {
                            Text(
                                text = "Page ${index + 1}",
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                text = "${bitmap.width} × ${bitmap.height}"
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

        Spacer(
            modifier = Modifier.height(12.dp)
        )

        Text(
            text = "Filter",
            style = MaterialTheme.typography.titleMedium
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            FilterMode.values().forEach { mode ->
                FilterChip(
                    selected = filter == mode,
                    onClick = {
                        onFilterChanged(mode)
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
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Quality.values().forEach { currentQuality ->
                FilterChip(
                    selected = quality == currentQuality,
                    onClick = {
                        onQualityChanged(currentQuality)
                    },
                    label = {
                        Text(text = currentQuality.name)
                    }
                )
            }
        }
    }
}

private fun createPreviewBitmap(
    source: Bitmap,
    filter: FilterMode
): Bitmap {
    val thumbnail =
        BitmapSizing.fit(
            source = source,
            maxDimension = 320
        )

    return ImageProcessor.process(
        source = thumbnail,
        mode = filter
    )
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

private fun decodeBitmapSafely(
    resolver: ContentResolver,
    uri: Uri,
    maxDimension: Int
): Bitmap? {
    return runCatching {
        val bounds =
            BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

        resolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(
                input,
                null,
                bounds
            )
        }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return@runCatching null
        }

        val sampleSize =
            calculateSampleSize(
                width = bounds.outWidth,
                height = bounds.outHeight,
                maxDimension = maxDimension
            )

        val options =
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
                inMutable = false
            }

        val decoded =
            resolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(
                    input,
                    null,
                    options
                )
            } ?: return@runCatching null

        BitmapSizing.fit(
            source = decoded,
            maxDimension = maxDimension
        )
    }.getOrNull()
}

private fun decodeFileSafely(
    file: File,
    maxDimension: Int
): Bitmap? {
    return runCatching {
        val bounds =
            BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

        BitmapFactory.decodeFile(
            file.absolutePath,
            bounds
        )

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return@runCatching null
        }

        val sampleSize =
            calculateSampleSize(
                width = bounds.outWidth,
                height = bounds.outHeight,
                maxDimension = maxDimension
            )

        val options =
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
                inMutable = false
            }

        val decoded =
            BitmapFactory.decodeFile(
                file.absolutePath,
                options
            ) ?: return@runCatching null

        BitmapSizing.fit(
            source = decoded,
            maxDimension = maxDimension
        )
    }.getOrNull()
}

private fun calculateSampleSize(
    width: Int,
    height: Int,
    maxDimension: Int
): Int {
    if (width <= 0 || height <= 0 || maxDimension <= 0) {
        return 1
    }

    var sampleSize = 1
    val largestDimension = maxOf(width, height)

    while (largestDimension / sampleSize > maxDimension) {
        sampleSize *= 2
    }

    return sampleSize.coerceAtLeast(1)
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
    val intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    context.startActivity(
        Intent.createChooser(
            intent,
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

        if (
            mode == FilterMode.ORIGINAL ||
            mode == FilterMode.COLOR
        ) {
            return output
        }

        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val matrix =
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

        paint.colorFilter =
            ColorMatrixColorFilter(matrix)

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
                        if (gray >= 170) 255 else 0

                    output.setPixel(
                        x,
                        y,
                        Color.rgb(
                            value,
                            value,
                            value
                        )
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
        val document =
            android.graphics.pdf.PdfDocument()

        try {
            pages.forEachIndexed { index, originalBitmap ->

                val filteredBitmap =
                    ImageProcessor.process(
                        source = originalBitmap,
                        mode = filter
                    )

                val processedBitmap =
                    BitmapSizing.fit(
                        source = filteredBitmap,
                        maxDimension = quality.maxDimension
                    )

                val landscape =
                    processedBitmap.width >= processedBitmap.height

                val pageWidth =
                    if (landscape) 842 else 595

                val pageHeight =
                    if (landscape) 595 else 842

                val pageInfo =
                    android.graphics.pdf.PdfDocument.PageInfo
                        .Builder(
                            pageWidth,
                            pageHeight,
                            index + 1
                        )
                        .create()

                val page =
                    document.startPage(pageInfo)

                page.canvas.drawColor(Color.WHITE)

                val scale =
                    minOf(
                        pageWidth.toFloat() /
                            processedBitmap.width.toFloat(),
                        pageHeight.toFloat() /
                            processedBitmap.height.toFloat()
                    )

                val drawWidth =
                    (processedBitmap.width * scale)
                        .toInt()
                        .coerceAtLeast(1)

                val drawHeight =
                    (processedBitmap.height * scale)
                        .toInt()
                        .coerceAtLeast(1)

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

                if (
                    processedBitmap !== originalBitmap &&
                    !processedBitmap.isRecycled
                ) {
                    processedBitmap.recycle()
                }

                if (
                    filteredBitmap !== originalBitmap &&
                    filteredBitmap !== processedBitmap &&
                    !filteredBitmap.isRecycled
                ) {
                    filteredBitmap.recycle()
                }
            }

            document.writeTo(output)
        } finally {
            document.close()
        }
    }
}
