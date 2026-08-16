package com.bitchat.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.R
import com.bitchat.android.core.ui.component.sheet.BitchatBottomSheet
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.internetp2p.InternetP2pSignaling
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Peer-to-peer direct link sheet: three ways to connect WITHOUT a favorite
 * relationship, backed by [InternetP2pSignaling]:
 *  - My link: QR code / share / copy of `bitchat-p2p://` URI (schemes B & C).
 *  - Scan: camera reads a peer's QR (scheme B receiver side).
 *  - Import: paste a shared link (scheme C receiver side) + geohash channel
 *    sweep (scheme A).
 *
 * Security note: links exchanged here are NOT authenticated by a favorite
 * relationship; the Noise handshake still authenticates the mesh session once
 * established, but the peer identity is unverified until it is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun P2pDirectSheet(
    isPresented: Boolean,
    onDismiss: () -> Unit,
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier
) {
    if (!isPresented) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(0) } // 0 = My link, 1 = Scan, 2 = Import
    var myLink by remember { mutableStateOf<String?>(null) }
    var importText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        myLink = InternetP2pSignaling.exportLinkUri()
    }

    BitchatBottomSheet(
        modifier = modifier,
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.Top
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.p2p_direct_link),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = BitchatFontFamily
                )
                androidx.compose.material3.TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.p2p_link_close), fontFamily = BitchatFontFamily)
                }
            }

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary,
                indicator = { tabPositions ->
                    TabRowDefaults.Indicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            ) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                    Text(stringResource(R.string.p2p_link_tab_mine), fontFamily = BitchatFontFamily, fontSize = 14.sp)
                }
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                    Text(stringResource(R.string.p2p_link_tab_scan), fontFamily = BitchatFontFamily, fontSize = 14.sp)
                }
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }) {
                    Text(stringResource(R.string.p2p_link_tab_import), fontFamily = BitchatFontFamily, fontSize = 14.sp)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Unverified-identity notice: these links bypass the favorite
            // relationship, so the peer is not mutually authenticated until
            // the Noise session binds identities.
            Text(
                text = stringResource(R.string.p2p_link_unverified_warning),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = BitchatFontFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            when (selectedTab) {
                0 -> MyLinkTab(
                    link = myLink,
                    onShare = { myLink?.let { shareLink(context, it) } },
                    onCopy = { myLink?.let { copyLink(context, it) } }
                )
                1 -> ScanTab(
                    onScan = { text ->
                        val peerID = InternetP2pSignaling.importLinkUri(text)
                        if (peerID != null) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.p2p_link_imported),
                                Toast.LENGTH_SHORT
                            ).show()
                            scope.launch {
                                viewModel.startPrivateChat(peerID)
                                onDismiss()
                            }
                        } else {
                            Toast.makeText(
                                context,
                                context.getString(R.string.p2p_link_unrecognized),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
                2 -> ImportTab(
                    viewModel = viewModel,
                    importText = importText,
                    onImportTextChange = { importText = it },
                    onImport = {
                        val peerID = InternetP2pSignaling.importLinkUri(importText.trim())
                        if (peerID != null) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.p2p_link_imported),
                                Toast.LENGTH_SHORT
                            ).show()
                            importText = ""
                            scope.launch {
                                viewModel.startPrivateChat(peerID)
                                onDismiss()
                            }
                        } else {
                            Toast.makeText(
                                context,
                                context.getString(R.string.p2p_link_unrecognized),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    onGeohashSweep = {
                        scope.launch {
                            val geo = (viewModel.selectedLocationChannel.value as? ChannelID.Location)
                                ?.channel?.geohash
                            val pubs = viewModel.geohashPeople.value.map { it.id }
                            val n = InternetP2pSignaling.searchGeohashChannel(geo ?: "", pubs)
                            Toast.makeText(
                                context,
                                if (n > 0) {
                                    context.getString(R.string.p2p_link_probing, n)
                                } else {
                                    context.getString(R.string.p2p_link_no_channel)
                                },
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun MyLinkTab(
    link: String?,
    onShare: () -> Unit,
    onCopy: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (link == null) {
            CircularProgressIndicator(modifier = Modifier.size(36.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Text("Building your link…", fontFamily = BitchatFontFamily)
        } else {
            P2pQrImage(data = link, size = 220.dp)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = link,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = BitchatFontFamily,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onShare) { Text("Share", fontFamily = BitchatFontFamily) }
                OutlinedButton(onClick = onCopy) { Text("Copy", fontFamily = BitchatFontFamily) }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun ScanTab(onScan: (String) -> Unit) {
    // Privacy: camera frames are analyzed in-memory by ML Kit; nothing is
    // persisted or transmitted. Only the decoded text is delivered to onScan.
    ScannerView(onScan = onScan)
}

@Composable
private fun ImportTab(
    viewModel: ChatViewModel,
    importText: String,
    onImportTextChange: (String) -> Unit,
    onImport: () -> Unit,
    onGeohashSweep: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Paste a bitchat-p2p:// link shared by a peer, or probe everyone in your current location channel.",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = BitchatFontFamily,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = importText,
            onValueChange = onImportTextChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("bitchat-p2p://…", fontFamily = BitchatFontFamily) },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = BitchatFontFamily)
        )
        Button(
            onClick = onImport,
            enabled = importText.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Import & connect", fontFamily = BitchatFontFamily)
        }
        OutlinedButton(
            onClick = onGeohashSweep,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Probe location-channel peers", fontFamily = BitchatFontFamily)
        }
    }
}

private fun shareLink(context: Context, link: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, link)
    }
    context.startActivity(Intent.createChooser(intent, "Share P2P link"))
}

private fun copyLink(context: Context, link: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("bitchat-p2p", link))
    Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
}

// ---------------------------------------------------------------------------
// Reusable QR + camera scanner pieces (mirror VerificationSheet's private
// helpers so the P2P sheet stays self-contained).
// ---------------------------------------------------------------------------

@Composable
private fun P2pQrImage(data: String, size: Dp) {
    val sizePx = with(LocalDensity.current) { size.toPx().toInt() }
    val bitmap = remember(data, sizePx) { p2pQrBitmap(data, sizePx) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(size)
        )
    }
}

private fun p2pQrBitmap(data: String, sizePx: Int): Bitmap? {
    if (data.isBlank() || sizePx <= 0) return null
    return try {
        val matrix = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, sizePx, sizePx)
        p2pBitmapFromMatrix(matrix)
    } catch (_: Exception) {
        null
    }
}

private fun p2pBitmapFromMatrix(matrix: BitMatrix): Bitmap {
    val width = matrix.width
    val height = matrix.height
    val bitmap = createBitmap(width, height)
    for (x in 0 until width) {
        for (y in 0 until height) {
            bitmap[x, y] =
                if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
    }
    return bitmap
}

@Composable
private fun ScannerView(onScan: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var lastValid by remember { mutableStateOf<String?>(null) }
    val cameraProviderFuture = remember { androidx.camera.lifecycle.ProcessCameraProvider.getInstance(context) }
    val cameraExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    val surfaceRequests = remember { MutableStateFlow<androidx.camera.core.SurfaceRequest?>(null) }
    val surfaceRequest by surfaceRequests.collectAsState(initial = null)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    val onCodeState = rememberUpdatedState(onScan)
    val analyzer = remember {
        P2pQrAnalyzer { text ->
            mainHandler.post {
                if (text == lastValid) return@post
                lastValid = text
                onCodeState.value(text)
            }
        }
    }

    DisposableEffect(Unit) {
        val executor = androidx.core.content.ContextCompat.getMainExecutor(context)
        var cameraProvider: androidx.camera.lifecycle.ProcessCameraProvider? = null

        cameraProviderFuture.addListener(
            {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider
                val preview = androidx.camera.core.Preview.Builder().build().also {
                    it.setSurfaceProvider { request -> surfaceRequests.value = request }
                }
                val analysis = androidx.camera.core.ImageAnalysis.Builder()
                    .setBackpressureStrategy(androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(cameraExecutor, analyzer) }

                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                    )
                }.onFailure {
                    android.util.Log.w("P2pDirectSheet", "Failed to bind camera: ${it.message}")
                }
            },
            executor
        )

        onDispose {
            surfaceRequests.value = null
            runCatching { cameraProvider?.unbindAll() }
            cameraExecutor.shutdown()
        }
    }

    surfaceRequest?.let { request ->
        androidx.camera.compose.CameraXViewfinder(
            surfaceRequest = request,
            implementationMode = androidx.camera.viewfinder.core.ImplementationMode.EMBEDDED,
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp)
        )
    }
}

private class P2pQrAnalyzer(
    private val onCode: (String) -> Unit
) : androidx.camera.core.ImageAnalysis.Analyzer {
    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    )

    @androidx.camera.core.ExperimentalGetImage
    override fun analyze(imageProxy: androidx.camera.core.ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                barcodes.firstOrNull()?.rawValue?.let { onCode(it) }
            }
            .addOnCompleteListener { imageProxy.close() }
    }
}
