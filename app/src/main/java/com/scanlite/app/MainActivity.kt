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
import androidx.compose.runtime.produceState
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

private data class ScanPage(
    val id: String,
    val uri: Uri? = null,
    val file: File? = null
)

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
        mutableStateOf<List<ScanPage>>(emptyList())
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
    val scope = rememberCoroutineScope()
    val snackbar = remember {
        SnackbarHostState()
    }

    val galleryLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetMultipleContents()
        ) { uris ->

            if (uris.isEmpty()) {
                return@rememberLauncherForActivityResult
            }

            val newPages = uris.mapIndexed { index, uri ->
                ScanPage(
                    id = "gallery-${System.currentTimeMillis()}-$index",
                    uri = uri
                )
            }

            pages = pages + newPages
            screen = "pages"
        }

    val pdfLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument(
                "application/pdf"
            )
        ) { destination ->

            if (destination == null || pages.isEmpty()) {
                return@rememberLauncherForActivityResult
            }

            scope.launch {
                runCatching {
                    val output =
                        context.contentResolver.openOutputStream(destination)
                            ?: error("Cannot open selected destination")

                    output.use { stream ->
                        PdfWriter.write(
                            resolver = context.contentResolver,
                            pages = pages,
                            output = stream,
                            filter = filter,
                            quality = quality
                        )
                    }

                    sharePdf(
                        context = context,
                        uri = destination
                    )
                }.onFailure { error ->
                    snackbar.showSnackbar(
                        "PDF export failed: ${
                            error.message ?: "unknown error"
                        }"
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
                    hostState = snackbar
                )
            }
        ) { padding ->

            when (screen) {
                "camera" -> {
                    CameraScreen(
                        padding = padding,
                        onCaptured = { file ->
                            pages = pages + ScanPage(
                                id = "camera-${file.name}",
                                file = file
                            )
                            screen = "pages"
                        },
                        onBack = {
                            screen = "home"
                        }
                    )
                }

                "pages" -> {
                    PageManager(
                        padding = padding,
                        pages = pages,
                        filter = filter,
                        quality = quality,
                        resolver = context.contentResolver,
                        onFilterChanged = {
                            filter = it
                        },
                        onQualityChanged = {
                            quality = it
                        },
                        onMove = { from, to ->
                            pages = pages.move(from, to)
                        },
                        onDelete = { index ->
                            pages = pages.filterIndexed { current, _ ->
                                current != index
                            }
                        },
                        onBack = {
                            screen = "home"
                        },
                        onExport = {
                            pdfLauncher.launch(
                                "ScanLite-${
                                    SimpleDateFormat(
                                        "yyyyMMdd-HHmmss",
                                        Locale.US
                                    ).format(Date())
                                }.pdf"
                            )
                        }
                    )
                }

                else -> {
                    HomeScreen(
                        padding = padding,
                        pages = pages,
                        onScan = {
                            screen = "camera"
                        },
                        onGallery = {
                            galleryLauncher.launch("image/*")
                        },
                        onOpenPages = {
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
    pages: List<ScanPage>,
    onScan: () -> Unit,
    onGallery: () -> Unit,
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
            text = "ScanLite",
            style = MaterialTheme.typography.headlineSmall
        )

        Text(
            text = "Scan and convert your documents locally."
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

            Text(text = "Scan with camera")
        }

        OutlinedButton(
            onClick = onGallery,
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

            Text(text = "Choose from gallery")
        }

        if (pages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No pages selected",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            HorizontalDivider()

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
                        text = "${pages.size} page(s) selected"
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraScreen(
    padding: PaddingValues,
    onCaptured: (File) -> Unit,
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

    var capture by remember {
        mutableStateOf<ImageCapture?>(null)
    }

    var camera by remember {
        mutableStateOf<Camera?>(null)
    }

    var cameraError by remember {
        mutableStateOf<String?>(null)
    }

    var torchEnabled by remember {
        mutableStateOf(false)
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
                cameraError = "Camera permission was denied."
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
                                .also {
                                    it.setSurfaceProvider(
                                        previewView.surfaceProvider
                                    )
                                }

                        val imageCapture =
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
                                imageCapture
                            )

                        if (!disposed) {
                            capture = imageCapture
                            camera = boundCamera
                            cameraError = null
                        }
                    }.onFailure {
                        if (!disposed) {
                            capture = null
                            camera = null
                            cameraError = "Unable to start camera."
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

            capture = null
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
                capture?.let { imageCapture ->
                    capturePhoto(
                        context = context,
                        imageCapture = imageCapture,
                        onCaptured = onCaptured
                    )
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(28.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = "Capture"
            )
        }
    }
}

private fun capturePhoto(
    context: Context,
    imageCapture: ImageCapture,
    onCaptured: (File) -> Unit
) {
    val target =
        runCatching {
            File.createTempFile(
                "scanlite-",
                ".jpg",
                context.filesDir
            )
        }.getOrNull() ?: return

    val options =
        ImageCapture.OutputFileOptions
            .Builder(target)
            .build()

    imageCapture.takePicture(
        options,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {

            override fun onError(
                exception: ImageCaptureException
            ) {
                target.delete()
            }

            override fun onImageSaved(
                outputFileResults: ImageCapture.OutputFileResults
            ) {
                if (target.exists() && target.length() > 0L) {
                    onCaptured(target)
                } else {
                    target.delete()
                }
            }
        }
    )
}

@Composable
private fun PageManager(
    padding: PaddingValues,
    pages: List<ScanPage>,
    filter: FilterMode,
    quality: Quality,
    resolver: ContentResolver,
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(
                items = pages,
                key = { _, page -> page.id }
            ) { index, page ->

                val preview by produceState<Bitmap?>(
                    initialValue = null,
                    key1 = page.id,
                    key2 = filter
                ) {
                    value = withContext(Dispatchers.IO) {
                        loadPageBitmap(
                            resolver = resolver,
                            page = page,
                            maxDimension = 320
                        )?.let { bitmap ->
                            processBitmap(
                                source = bitmap,
                                mode = filter
                            )
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (preview != null) {
                            Image(
                                bitmap = preview!!.asImageBitmap(),
                                contentDescription = "Page ${index + 1}",
                                modifier = Modifier.size(72.dp, 96.dp)
                            )
                        } else {
                            Box(
                                modifier = Modifier.size(72.dp, 96.dp),
