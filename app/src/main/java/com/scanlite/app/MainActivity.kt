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
import androidx.activity.compose.PickVisualMediaRequest
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
import androidx.compose.material.icons.filled.Folder
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

    var isLoading by remember {
        mutableStateOf(false)
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember {
        SnackbarHostState()
    }

    fun addUris(uris: List<Uri>) {
        if (uris.isEmpty()) {
            return
        }

        scope.launch {
            isLoading = true

            val decoded =
                withContext(Dispatchers.IO) {
                    uris.mapNotNull { uri ->
                        decodeBitmapSafely(
                            resolver = context.contentResolver,
                            uri = uri,
                            maxDimension = Quality.BALANCED.maxDimension
                        )
                    }
                }

            isLoading = false

            if (decoded.isEmpty()) {
                snackbar.showSnackbar(
                    "لم يتمكن التطبيق من قراءة الصور المحددة"
                )
            } else {
                pages = pages + decoded
                screen = "pages"
            }
        }
    }

    val galleryLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickMultipleVisualMedia(
                maxItems = 20
            )
        ) { uris ->
            addUris(uris)
        }

    val fileLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenMultipleDocuments()
        ) { uris ->
            addUris(uris)
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
                isLoading = true

                runCatching {
                    withContext(Dispatchers.IO) {
                        val output =
                            context.contentResolver.openOutputStream(
                                destination
                            ) ?: error("تعذر فتح مكان الحفظ")

                        output.use { stream ->
                            PdfWriter.write(
                                pages = pages,
                                output = stream,
                                filter = filter,
                                quality = quality
                            )
                        }
                    }

                    sharePdf(
                        context = context,
                        uri = destination
                    )
                }.onFailure { error ->
                    snackbar.showSnackbar(
                        "فشل إنشاء PDF: ${
                            error.message ?: "خطأ غير معروف"
                        }"
                    )
                }

                isLoading = false
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

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("جاري تجهيز الصور...")
                }
            } else {
                when (screen) {
                    "camera" -> {
                        CameraScreen(
                            padding = padding,
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
                            padding = padding,
                            pages = pages,
                            filter = filter,
                            quality = quality,
                            onFilter = {
                                filter = it
                            },
                            onQuality = {
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
                            padding = padding,
                            pages = pages,
                            onScan = {
                                screen = "camera"
                            },
                            onGallery = {
                                galleryLauncher.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            },
                            onFiles = {
                                fileLauncher.launch(
                                    arrayOf("image/*")
                                )
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
}

@Composable
private fun HomeScreen(
    padding: PaddingValues,
    pages: List<Bitmap>,
    onScan: () -> Unit,
    onGallery: () -> Unit,
    onFiles: () -> Unit,
    onOpenPages: () -> Unit
) {
    Column(
        modifier = Modifier
            .padding(padding)
            .padding(20.dp)
            .fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "ScanLite",
            style = MaterialTheme.typography.headlineSmall
        )

        Text(
            text = "ماسح مستندات يعمل محليًا على هاتفك"
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

            Text("فتح الكاميرا")
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

            Text("اختيار من الاستديو")
        }

        OutlinedButton(
            onClick = onFiles,
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null
            )

            Spacer(
                modifier = Modifier.width(8.dp)
            )

            Text("اختيار من الملفات")
        }

        if (pages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "لا توجد صفحات بعد",
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
                        text = "${pages.size} صفحة جاهزة"
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

    var torch by remember {
        mutableStateOf(false)
    }

    var error by remember {
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
                error = "يجب السماح باستخدام الكاميرا"
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
                            error = null
                        }
                    }.onFailure {
                        if (!disposed) {
                            imageCapture = null
                            camera = null
                            error = "تعذر تشغيل الكاميرا"
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

        if (!hasPermission || error != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = error ?: "يجب السماح باستخدام الكاميرا",
                    color = Color.WHITE
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
                        if (hasPermission) {
                            "فتح الإعدادات"
                        } else {
                            "السماح بالكاميرا"
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
                    contentDescription = "رجوع",
                    tint = Color.WHITE
                )
            }

            IconButton(
                onClick = {
                    torch = !torch
                    camera?.cameraControl?.enableTorch(torch)
                }
            ) {
                Icon(
                    imageVector = if (torch) {
                        Icons.Default.FlashOn
                    } else {
                        Icons.Default.FlashOff
                    },
                    contentDescription = "الفلاش",
                    tint = Color.WHITE
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
                contentDescription = "التقاط"
            )
        }
    }
}

private fun capturePhoto(
    context: Context,
    capture: ImageCapture,
    onCaptured: (Bitmap) -> Unit
) {
    val file =
        runCatching {
            File.createTempFile(
                "scan_",
                ".jpg",
                context.cacheDir
            )
        }.getOrNull() ?: return

    val options =
        ImageCapture.OutputFileOptions.Builder(file)
            .build()

    capture.takePicture(
        options,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {

            override fun onError(
                exception: ImageCaptureException
            ) {
                file.delete()
            }

            override fun onImageSaved(
                outputFileResults: ImageCapture.OutputFileResults
            ) {
                try {
                    val bitmap =
                        decodeFileSafely(
                            file = file,
                            maxDimension = Quality.BALANCED.maxDimension
                        )

                    if (bitmap != null && !bitmap.isRecycled) {
                        onCaptured(bitmap)
                    }
                } finally {
                    file.delete()
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
            .padding(12.dp)
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
                        contentDescription = "رجوع"
                    )
                }

                Text(
                    text = "الصفحات: ${pages.size}",
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

                Text("PDF")
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 440.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(
                items = pages
            ) { index, bitmap ->

                val thumbnail =
                    remember(bitmap, filter) {
                        val small =
                            BitmapSizing.fit(
                                source = bitmap,
                                maxDimension = 300
                            )

                        ImageProcessor.process(
                            source = small,
                            mode = filter
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
                            bitmap = thumbnail.asImageBitmap(),
                            contentDescription = "صفحة ${index + 1}",
                            modifier = Modifier.size(70.dp, 92.dp)
                        )

                        Spacer(
                            modifier = Modifier.width(8.dp)
                        )

                        Column(
                            modifier = Modifier.width(90.dp)
                        ) {
                            Text(
                                text = "صفحة ${index + 1}",
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
                                contentDescription = "أعلى"
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
                                contentDescription = "أسفل"
                            )
                        }

                        IconButton(
                            onClick = {
                                onDelete(index)
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "حذف"
                            )
                        }
                    }
                }
            }
        }

        Spacer(
            modifier = Modifier.height(10.dp)
        )

        Text(
            text = "المعالجة",
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
                        onFilter(mode)
                    },
                    label = {
                        Text(
                            mode.name.replace('_', ' '),
                            maxLines = 1
                        )
                    }
                )
            }
        }

        Text(
            text = "الجودة",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Quality.values().forEach { current ->
                FilterChip(
                    selected = quality == current,
                    onClick = {
                        onQuality(current)
                    },
                    label = {
                        Text(current.name)
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

        val sample =
            calculateSampleSize(
                width = bounds.outWidth,
                height = bounds.outHeight,
                maxDimension = maxDimension
            )

        val options =
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
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

        val sample =
            calculateSampleSize(
                width = bounds.outWidth,
                height = bounds.outHeight,
                maxDimension = maxDimension
            )

        val options =
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
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

    var sample = 1
    val largest = maxOf(width, height)

    while (largest / sample > maxDimension) {
        sample *= 2
    }

    return sample
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
    val sendIntent =
        Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    context.startActivity(
        Intent.createChooser(
            sendIntent,
            "مشاركة PDF"
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
        val document =
            android.graphics.pdf.PdfDocument()

        try {
            pages.forEachIndexed { index, original ->

                val filtered =
                    ImageProcessor.process(
                        source = original,
                        mode = filter
                    )

                val processed =
                    BitmapSizing.fit(
                        source = filtered,
                        maxDimension = quality.maxDimension
                    )

                val landscape =
                    processed.width >= processed.height

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
                            processed.width.toFloat(),
                        pageHeight.toFloat() /
                            processed.height.toFloat()
                    )

                val drawWidth =
                    (processed.width * scale)
                        .toInt()
                        .coerceAtLeast(1)

                val drawHeight =
                    (processed.height * scale)
                        .toInt()
                        .coerceAtLeast(1)

                val left =
                    (pageWidth - drawWidth) / 2

                val top =
                    (pageHeight - drawHeight) / 2

                page.canvas.drawBitmap(
                    processed,
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
                    processed !== original &&
                    !processed.isRecycled
                ) {
                    processed.recycle()
                }

                if (
                    filtered !== original &&
                    filtered !== processed &&
                    !filtered.isRecycled
                ) {
                    filtered.recycle()
                }
            }

            document.writeTo(output)
        } finally {
            document.close()
        }
    }
}
