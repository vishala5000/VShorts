package com.vshorts.app

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            requestStoragePermission()
            copyAssetsToInternal()
            setContent { VShortsApp() }
        } catch (e: Throwable) {
            e.printStackTrace()
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT <= 32) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 101)
            }
        }
    }

    private fun copyAssetsToInternal() {
        val destDir = File(filesDir, "piper")
        if (!destDir.exists()) destDir.mkdirs()

        val asset = "en_US-kristin-medium.onnx"
        val destFile = File(destDir, asset)
        if (!destFile.exists() || destFile.length() == 0L) {
            try {
                assets.open("piper/$asset").use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }
}

@Composable
fun VShortsApp() {
    val context = LocalContext.current
    var text by remember { mutableStateOf("Hello, this is Kristin speaking!") }
    var progress by remember { mutableStateOf(0f) }
    var isGenerating by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var isPaused by remember { mutableStateOf(false) }
    var audioFilePath by remember { mutableStateOf<String?>(null) }
    var statusText by remember { mutableStateOf("Initializing Piper TTS...") }

    val ttsManager = remember { PiperTTSManager(context) }
    val mediaPlayer = remember { android.media.MediaPlayer() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            ttsManager.initialize { status ->
                statusText = status
            }
        }
    }

    DisposableEffect(mediaPlayer) {
        onDispose { 
            mediaPlayer.release() 
            ttsManager.shutdown()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("VShorts - Piper TTS (100% Offline)", style = MaterialTheme.typography.headlineMedium)
        Text(statusText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Enter text (no limit)") },
            modifier = Modifier.fillMaxWidth().heightIn(min=120.dp),
            maxLines = Int.MAX_VALUE
        )

        if (isGenerating || progress > 0f) {
            Column {
                Text("Generation Progress: ${(progress * 100).toInt()}%")
                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier.fillMaxWidth().height(8.dp)
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    if (text.isNotBlank() && ttsManager.isReady) {
                        isGenerating = true
                        progress = 0f
                        scope.launch {
                            val file = ttsManager.generateSpeech(text) { prog -> 
                                progress = prog 
                            }
                            audioFilePath = file?.absolutePath
                            isGenerating = false
                            if (file != null) {
                                Toast.makeText(context, "✅ Generation Complete", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "❌ Generation Failed", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else if (!ttsManager.isReady) {
                        Toast.makeText(context, "TTS still initializing...", Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = !isGenerating && ttsManager.isReady
            ) { Text("Generate Speech") }

            Button(
                onClick = {
                    audioFilePath?.let { path ->
                        downloadToInternalStorage(context, path)
                    } ?: run {
                        Toast.makeText(context, "Generate speech first", Toast.LENGTH_SHORT).show()
                    }
                }
            ) { Text("Download") }
        }

        if (audioFilePath != null) {
            Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(4.dp)) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Player", style = MaterialTheme.typography.titleMedium)

                    IconButton(onClick = {
                        if (!isPlaying) {
                            mediaPlayer.reset()
                            mediaPlayer.setDataSource(audioFilePath)
                            mediaPlayer.prepare()
                            mediaPlayer.start()
                            isPlaying = true
                            isPaused = false
                        } else if (isPaused) {
                            mediaPlayer.start()
                            isPaused = false
                        }
                    }) {
                        Text(if (isPlaying && !isPaused) "⏸" else "▶", style = MaterialTheme.typography.headlineMedium)
                    }

                    IconButton(onClick = {
                        if (isPlaying && !isPaused) {
                            mediaPlayer.pause()
                            isPaused = true
                        }
                    }, enabled = isPlaying && !isPaused) {
                        Text("⏸", style = MaterialTheme.typography.headlineMedium)
                    }

                    IconButton(onClick = {
                        if (isPlaying) {
                            mediaPlayer.stop()
                            mediaPlayer.reset()
                            isPlaying = false
                            isPaused = false
                        }
                    }, enabled = isPlaying) {
                        Text("⏹", style = MaterialTheme.typography.headlineMedium)
                    }
                }
            }
        }
    }
}

class PiperTTSManager(private val context: Context) {
    var isReady = false
    private var tts: OfflineTts? = null

    fun initialize(onStatus: (String) -> Unit) {
        try {
            onStatus("Loading high-quality Piper model...")

            val modelDir = File(context.filesDir, "piper").absolutePath
            val modelName = "en_US-kristin-medium.onnx"

            val vitsConfig = OfflineTtsVitsModelConfig(
                model = "$modelDir/$modelName",
                lexicon = "",
                tokens = "",
                noiseScale = 0.667f,
                noiseScaleW = 0.8f,
                lengthScale = 1.0f
            )

            val modelConfig = OfflineTtsModelConfig(
                vits = vitsConfig,
                numThreads = 2,
                debug = false,
                provider = "cpu"
            )

            val config = OfflineTtsConfig(
                model = modelConfig,
                ruleFsts = "",
                ruleFars = "",
                maxNumSentences = 1
            )

            tts = OfflineTts(config = config)
            isReady = true
            onStatus("✅ Piper TTS Ready - Voice: Kristin (High Quality)")
        } catch (e: Throwable) {
            e.printStackTrace()
            isReady = false
            onStatus("❌ Init failed: ${e.message}")
        }
    }

    suspend fun generateSpeech(text: String, onProgress: (Float) -> Unit): File? {
        return withContext(Dispatchers.IO) {
            try {
                if (!isReady || tts == null) return@withContext null

                onProgress(0.1f)
                val audio = tts!!.generate(text = text, sid = 0, speed = 1.0f)
                onProgress(0.8f)

                val outputFile = writeWavFile(context, audio.samples, audio.sampleRate)
                onProgress(1.0f)
                outputFile
            } catch (e: Throwable) {
                e.printStackTrace()
                null
            }
        }
    }

    private fun writeWavFile(context: Context, samples: FloatArray, sampleRate: Int): File {
        val outputFile = File(context.cacheDir, "piper_output.wav")
        FileOutputStream(outputFile).use { fos ->
            val numChannels = 1
            val bitsPerSample = 16
            val byteRate = sampleRate * numChannels * bitsPerSample / 8
            val blockAlign = numChannels * bitsPerSample / 8
            val dataSize = samples.size * blockAlign

            fos.write("RIFF".toByteArray())
            fos.write(intToByteArray(36 + dataSize))
            fos.write("WAVE".toByteArray())
            fos.write("fmt ".toByteArray())
            fos.write(intToByteArray(16))
            fos.write(shortToByteArray(1))
            fos.write(shortToByteArray(numChannels.toShort()))
            fos.write(intToByteArray(sampleRate))
            fos.write(intToByteArray(byteRate))
            fos.write(shortToByteArray(blockAlign.toShort()))
            fos.write(shortToByteArray(bitsPerSample.toShort()))
            fos.write("data".toByteArray())
            fos.write(intToByteArray(dataSize))

            val buffer = ByteArray(2)
            for (sample in samples) {
                val clamped = sample.coerceIn(-1.0f, 1.0f)
                val pcmVal = (clamped * 32767.0f).toInt().toShort()
                buffer[0] = (pcmVal.toInt() and 0xFF).toByte()
                buffer[1] = ((pcmVal.toInt() shr 8) and 0xFF).toByte()
                fos.write(buffer)
            }
        }
        return outputFile
    }

    private fun intToByteArray(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte()
    )

    private fun shortToByteArray(value: Short): ByteArray = byteArrayOf(
        (value.toInt() and 0xFF).toByte(),
        ((value.toInt() shr 8) and 0xFF).toByte()
    )

    fun shutdown() {
        tts?.release()
    }
}

private fun downloadToInternalStorage(context: Context, sourcePath: String) {
    try {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "vshorts_${System.currentTimeMillis()}.wav")
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            resolver.openOutputStream(it)?.use { outputStream ->
                File(sourcePath).inputStream().copyTo(outputStream)
            }
            Toast.makeText(context, "✅ Saved to Downloads", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Throwable) {
        Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
    }
}