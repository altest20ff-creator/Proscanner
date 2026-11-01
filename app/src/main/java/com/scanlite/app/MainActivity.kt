package com.scanlite.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color as ComposeColor
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

internal enum class FilterMode {
    ORIGINAL,
    AUTO_ENHANCE,
    GRAYSCALE,
    BLACK_WHITE,
    DOCUMENT,
    MAGIC_COLOR,
    PHOTO,
    COLOR
}

private const val MAX_PAGES = 20
private const val TAG = "ScanLite.MainActivity"
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            ScanLiteApp()
        }
    }
}

private fun createThumbnail(
    source: Bitmap,
    filter: FilterMode
): Bitmap {
    val smallBitmap = BitmapSizing.fit(source, maxDimension = 300)
    val cropped = if (filter == FilterMode.DOCUMENT) {
        DocumentProcessor.autoCrop(smallBitmap)
    } else {
        smallBitmap
    }
    val thumbnail = ImageProcessor.process(cropped, filter)
    if (cropped !== smallBitmap && cropped !== source && cropped !== thumbnail && !cropped.isRecycled) {
        cropped.recycle()
    }
    if (smallBitmap !== source && smallBitmap !== cropped && smallBitmap !== thumbnail && !smallBitmap.isRecycled) {
        smallBitmap.recycle()
    }
    return thumbnail
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

    var selectedFilter by remember {
        mutableStateOf(FilterMode.DOCUMENT)
    }

    var selectedQuality by remember {
        mutableStateOf(Quality.BALANCED)
    }

    var isLoading by remember {
        mutableStateOf(false)
    }

    var ocrText by remember {
        mutableStateOf<String?>(null)
    }

    var savedDocuments by remember {
        mutableStateOf<List<SavedDocument>>(emptyList())
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember {
        SnackbarHostState()
    }

    LaunchedEffect(Unit) {
        savedDocuments = withContext(Dispatchers.IO) {
            SavedDocumentStore.list(context.filesDir)
        }
    }

    fun importImages(uris: List<Uri>) {
        if (uris.isEmpty() || isLoading) {
            return
        }

        isLoading = true
        scope.launch {
            val remainingSlots = (MAX_PAGES - pages.size).coerceAtLeast(0)
            val decodedPages = if (remainingSlots == 0) {
                emptyList()
            } else {
                withContext(Dispatchers.IO) {
                    buildList {
                        uris.take(remainingSlots).forEach { uri ->
                            val decoded = ImageDecoder.decodeUri(context.contentResolver, uri)
                            if (decoded != null) {
                                add(decoded)
                            } else {
                                Log.e(TAG, "image import failed for uri=$uri")
                            }
                        }
                    }
                }
            }

            fun importPdf(uri: Uri) {
                if (isLoading) return
                isLoading = true
                scope.launch {
                    val remainingSlots = (MAX_PAGES - pages.size).coerceAtLeast(0)
                    val importedPages = if (remainingSlots == 0) {
                        emptyList()
                    } else {
                        withContext(Dispatchers.IO) {
                            PdfImporter.importPages(
                                resolver = context.contentResolver,
                                uri = uri,
                                maxPages = remainingSlots
                            )
                        }
                    }
                    isLoading = false
                    if (importedPages.isEmpty()) {
                        snackbarHostState.showSnackbar("تعذر قراءة ملف PDF")
                    } else {
                        pages = pages + importedPages
                        screen = "pages"
                    }
                }
            }

            isLoading = false

            if (decodedPages.isEmpty()) {
                snackbarHostState.showSnackbar(
                    message = if (remainingSlots == 0) {
                        "تم الوصول إلى الحد الأقصى لعدد الصفحات"
                    } else {
                        "تعذر قراءة الصور المحددة"
                    }
                )
            } else {
                pages = pages + decodedPages
                screen = "pages"

                if (decodedPages.size < uris.size) {
                    snackbarHostState.showSnackbar(
                        message = "تمت إضافة ${decodedPages.size} صورة فقط لتقليل استهلاك الذاكرة"
                    )
                }
            }
        }
    }

    val imagePickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenMultipleDocuments()
        ) { uris ->
            uris.forEach { uri ->
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (error: SecurityException) {
                    Log.w(TAG, "persistable permission unavailable for uri=$uri", error)
                }
            }
            importImages(uris)
        }

    val pdfImportLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument()
        ) { uri ->
            uri?.let(::importPdf)
        }

    val pdfLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument(
                "application/pdf"
            )
        ) { destinationUri ->

            if (destinationUri == null || pages.isEmpty()) {
                return@rememberLauncherForActivityResult
            }

            scope.launch {
                isLoading = true

                runCatching {
                    val savedDocument = withContext(Dispatchers.IO) {
                        val output =
                            context.contentResolver.openOutputStream(
                                destinationUri
                            ) ?: error("تعذر فتح مكان الحفظ")

                        output.use { stream ->
                            PdfWriter.write(
                                pages = pages,
                                output = stream,
                                filter = selectedFilter,
                                quality = selectedQuality
                            )
                        }
                        SavedDocumentStore.copyFromUri(
                            resolver = context.contentResolver,
                            uri = destinationUri,
                            contextFilesDir = context.filesDir,
                            fileName = FileNaming.pdf(
                                prefix = "ScanLite",
                                timestamp = currentTimestamp()
                            )
                        )
                    }
                    savedDocument?.let {
                        savedDocuments = withContext(Dispatchers.IO) {
                            SavedDocumentStore.list(context.filesDir)
                        }
                    }

                    sharePdf(
                        context = context,
                        uri = destinationUri
                    )
                }.onFailure { error ->
                    snackbarHostState.showSnackbar(
                        message = "فشل إنشاء PDF: ${
                            error.message ?: "خطأ غير معروف"
                        }"
                    )
                    Log.e(TAG, "PDF export failed", error)
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
                    hostState = snackbarHostState
                )
            }
        ) { paddingValues ->

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .padding(paddingValues)
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "جاري تجهيز الصور..."
                    )
                }
            } else {
                when (screen) {
                    "camera" -> {
                        CameraScreen(
                            padding = paddingValues,
                            onCaptured = { bitmap ->
                                if (pages.size < MAX_PAGES) {
                                    pages = pages + bitmap
                                    screen = "pages"
                                } else {
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            message = "تم الوصول إلى الحد الأقصى لعدد الصفحات"
                                        )
                                    }
                                }
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
                            filter = selectedFilter,
                            quality = selectedQuality,
                            onFilterChanged = {
                                selectedFilter = it
                            },
                            onQualityChanged = {
                                selectedQuality = it
                            },
                            onOcr = {
                                if (!isLoading) {
                                    scope.launch {
                                        isLoading = true
                                        try {
                                            ocrText = withContext(Dispatchers.Default) {
                                                OcrEngine.recognize(context, pages)
                                            }
                                        } catch (error: Exception) {
                                            Log.e(TAG, "OCR request failed", error)
                                            snackbarHostState.showSnackbar("تعذر استخراج النص")
                                        } finally {
                                            isLoading = false
                                        }
                                    }
                                }
                            },
                            onMove = { from, to ->
                                pages = pages.move(from, to)
                            },
                            onRotate = { index ->
                                pages = pages.mapIndexed { currentIndex, page ->
                                    if (currentIndex == index) {
                                        BitmapTransforms.rotate(page, 90)
                                    } else {
                                        page
                                    }
                                }
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
                                pdfLauncher.launch(
                                    FileNaming.pdf(
                                        prefix = "ScanLite",
                                        timestamp = currentTimestamp()
                                    )
                                )
                            }
                        )
                    }

                    "documents" -> {
                        SavedDocumentsScreen(
                            padding = paddingValues,
                            documents = savedDocuments,
                            onDelete = { document ->
                                if (SavedDocumentStore.delete(document)) {
                                    savedDocuments = savedDocuments.filterNot {
                                        it.file == document.file
                                    }
                                }
                            },
                            onBack = { screen = "home" },
                            onShare = { document ->
                                shareLocalPdf(context, document.file)
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
                                imagePickerLauncher.launch(
                                    arrayOf("image/*")
                                )
                            },
                            onImportPdf = {
                                pdfImportLauncher.launch(
                                    arrayOf("application/pdf")
                                )
                            },
                            onOpenPages = {
                                if (pages.isNotEmpty()) {
                                    screen = "pages"
                                }
                            },
                            onOpenDocuments = {
                                screen = "documents"
                            }
                        )
                    }

                }

                ocrText?.let { text ->
                    AlertDialog(
                        onDismissRequest = { ocrText = null },
                        title = { Text("النص المستخرج") },
                        text = {
                            SelectionContainer {
                                Text(text.ifBlank { "لم يتم العثور على نص" })
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    val clipboard = context.getSystemService(
                                        Context.CLIPBOARD_SERVICE
                                    ) as ClipboardManager
                                    clipboard.setPrimaryClip(
                                        ClipData.newPlainText("ScanLite OCR", text)
                                    )
                                    ocrText = null
                                }
                            ) {
                                Text("نسخ")
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, text)
                                    }
                                    context.startActivity(
                                        Intent.createChooser(sendIntent, "مشاركة النص")
                                    )
                                }
                            ) {
                                Text("مشاركة")
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
    onImportPdf: () -> Unit,
    onOpenPages: () -> Unit,
    onOpenDocuments: () -> Unit
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

            Text(
                text = "فتح الكاميرا"
            )
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

            Text(
                text = "اختيار من الاستديو أو الملفات"
            )
        }

        OutlinedButton(
            onClick = onImportPdf,
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PictureAsPdf,
                contentDescription = null
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("استيراد PDF")
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

        OutlinedButton(
            onClick = onOpenDocuments,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("المستندات المحفوظة")
        }
    }
}

@Composable
private fun SavedDocumentsScreen(
    padding: PaddingValues,
    documents: List<SavedDocument>,
    onDelete: (SavedDocument) -> Unit,
    onBack: () -> Unit,
    onShare: (SavedDocument) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val visibleDocuments = documents.filter {
        it.name.contains(query, ignoreCase = true)
    }

    Column(
        modifier = Modifier
            .padding(padding)
            .padding(12.dp)
            .fillMaxSize()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "رجوع")
            }
            Text("المستندات المحفوظة", style = MaterialTheme.typography.titleLarge)
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("بحث بالاسم") },
            singleLine = true
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(visibleDocuments, key = { it.file.absolutePath }) { document ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = document.name,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { onShare(document) }) {
                            Text("مشاركة")
                        }
                        IconButton(onClick = { onDelete(document) }) {
                            Icon(Icons.Default.Delete, contentDescription = "حذف")
                        }
                    }
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
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
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

    var isCapturing by remember {
        mutableStateOf(false)
    }

    val previewView = remember {
        PreviewView(context)
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { granted ->
            hasCameraPermission = granted

            if (!granted) {
                cameraError = "يجب السماح باستخدام الكاميرا"
            }
        }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(
                Manifest.permission.CAMERA
            )
        }
    }

    DisposableEffect(
        hasCameraPermission,
        lifecycleOwner
    ) {
        var disposed = false
        var providerFuture: com.google.common.util.concurrent.ListenableFuture<ProcessCameraProvider>? = null

        if (hasCameraPermission) {
            val future = ProcessCameraProvider.getInstance(context)
            providerFuture = future

            future.addListener(
                {
                    if (!disposed) {
                        runCatching {
                            val provider = future.get()

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
                                    .setTargetRotation(
                                        previewView.display?.rotation
                                            ?: android.view.Surface.ROTATION_0
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
                        }.onFailure { error ->
                            Log.e(TAG, "CameraX binding failed", error)
                            if (!disposed) {
                                imageCapture = null
                                camera = null
                                cameraError = "تعذر تشغيل الكاميرا"
                            }
                        }
                    }
                },
                ContextCompat.getMainExecutor(context)
            )
        }

        onDispose {
            disposed = true

            if (providerFuture?.isDone == true) {
                try {
                    providerFuture?.get()?.unbindAll()
                } catch (error: Exception) {
                    Log.e(TAG, "CameraX unbind failed", error)
                }
            }

            imageCapture = null
            camera = null
        }
    }

    Box(
        modifier = Modifier
            .padding(padding)
            .fillMaxSize()
            .background(ComposeColor.Black)
    ) {
        if (hasCameraPermission) {
            AndroidView(
                factory = {
                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (!hasCameraPermission || cameraError != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = cameraError
                        ?: "يجب السماح باستخدام الكاميرا",
                    color = ComposeColor.White
                )

                Spacer(
                    modifier = Modifier.height(12.dp)
                )

                Button(
                    onClick = {
                        if (!hasCameraPermission) {
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
                        text = if (hasCameraPermission) {
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
                    tint = ComposeColor.White
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
                    contentDescription = "الفلاش",
                    tint = ComposeColor.White
                )
            }
        }

        FloatingActionButton(
            onClick = {
                if (isCapturing) {
                    return@FloatingActionButton
                }
                imageCapture?.let { capture ->
                    isCapturing = true
                    capturePhoto(
                        context = context,
                        capture = capture,
                        scope = scope,
                        onCaptured = onCaptured,
                        onError = {
                            isCapturing = false
                            cameraError = "تعذر قراءة الصورة الملتقطة"
                        },
                        onFinished = {
                            isCapturing = false
                        }
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
    scope: kotlinx.coroutines.CoroutineScope,
    onCaptured: (Bitmap) -> Unit,
    onError: () -> Unit,
    onFinished: () -> Unit
) {
    val file = try {
            File.createTempFile(
                "scan_",
                ".jpg",
                context.cacheDir
            )
        } catch (error: Exception) {
            Log.e(TAG, "Camera temp file creation failed", error)
            onError()
            return
        }

    val outputOptions =
        ImageCapture.OutputFileOptions.Builder(file)
            .build()

    capture.takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {

            override fun onError(
                exception: ImageCaptureException
            ) {
                file.delete()
                onError()
            }

            override fun onImageSaved(
                outputFileResults: ImageCapture.OutputFileResults
            ) {
                scope.launch(Dispatchers.IO) {
                    val bitmap = ImageDecoder.decodeFile(file)
                    file.delete()
                    withContext(Dispatchers.Main) {
                        if (bitmap != null && !bitmap.isRecycled) {
                            onCaptured(bitmap)
                        } else {
                            onError()
                        }
                        onFinished()
                    }
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
    onOcr: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onRotate: (Int) -> Unit,
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

                Text(
                    text = "PDF"
                )
            }

            OutlinedButton(
                onClick = onOcr,
                enabled = pages.isNotEmpty()
            ) {
                Text("OCR")
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
                        mutableStateOf<Bitmap?>(null)
                    }

                LaunchedEffect(bitmap, filter) {
                    thumbnail.value = withContext(Dispatchers.Default) {
                        createThumbnail(bitmap, filter)
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        thumbnail.value?.let { currentThumbnail ->
                            Image(
                                bitmap = currentThumbnail.asImageBitmap(),
                                contentDescription = "صفحة ${index + 1}",
                                modifier = Modifier.size(70.dp, 92.dp)
                            )
                        } ?: Box(
                            modifier = Modifier.size(70.dp, 92.dp)
                        )

                        DisposableEffect(thumbnail.value) {
                            val bitmapToRecycle = thumbnail.value
                            onDispose {
                                if (
                                    bitmapToRecycle != null &&
                                    bitmapToRecycle !== bitmap &&
                                    !bitmapToRecycle.isRecycled
                                ) {
                                    bitmapToRecycle.recycle()
                                }
                            }
                        }

                        Spacer(
                            modifier = Modifier.width(8.dp)
                        )

                        Column(
                            modifier = Modifier.width(88.dp)
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
                                onRotate(index)
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.RotateRight,
                                contentDescription = "تدوير"
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
            text = "الجودة",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp)
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
                        Text(
                            text = currentQuality.name
                        )
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

private fun currentTimestamp(): String {
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

        private fun shareLocalPdf(
            context: Context,
            file: File
        ) {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.files",
                file
            )
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(sendIntent, "مشاركة PDF"))
        }

    context.startActivity(
        Intent.createChooser(
            sendIntent,
            "مشاركة PDF"
        )
    )
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

                val documentBitmap =
                    if (filter == FilterMode.DOCUMENT) {
                        DocumentProcessor.autoCrop(originalBitmap)
                    } else {
                        originalBitmap
                    }

                val filteredBitmap =
                    ImageProcessor.process(
                        source = documentBitmap,
                        mode = filter
                    )

                val processedBitmap =
                    BitmapSizing.fit(
                        source = filteredBitmap,
                        maxDimension = quality.maxDimension
                    )

                val isLandscape =
                    processedBitmap.width >= processedBitmap.height

                val pageWidth =
                    if (isLandscape) {
                        842
                    } else {
                        595
                    }

                val pageHeight =
                    if (isLandscape) {
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

                if (
                    documentBitmap !== originalBitmap &&
                    documentBitmap !== filteredBitmap &&
                    documentBitmap !== processedBitmap &&
                    !documentBitmap.isRecycled
                ) {
                    documentBitmap.recycle()
                }
            }

            document.writeTo(output)
        } finally {
            document.close()
        }
    }
}
