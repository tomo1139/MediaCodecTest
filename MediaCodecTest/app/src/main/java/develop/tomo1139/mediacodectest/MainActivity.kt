package develop.tomo1139.mediacodectest

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Environment
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.databinding.DataBindingUtil
import develop.tomo1139.mediacodectest.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream
import java.lang.Exception
import java.nio.ByteBuffer


class MainActivity : AppCompatActivity() {
    private val binding by lazy {
        DataBindingUtil.setContentView<ActivityMainBinding>(
            this,
            R.layout.activity_main
        )
    }
    private var fileOutputStream: FileOutputStream? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.button.setOnClickListener {
            showFileSelectDialog()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_CODE_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults.first() == PackageManager.PERMISSION_GRANTED) {
                FilePickerUtil.showGallery(this, REQUEST_CODE_FILE_SELECT)
            } else {
                D.p("need permission")
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return

        if (requestCode == REQUEST_CODE_FILE_SELECT) {
            data?.data ?: return

            val path = FilePickerUtil.getPath(this, data.data)

            val outputFilePath = getExternalFilesDir(null)
            val outputFile = File(outputFilePath, RAW_AUDIO_FILE_NAME)
            //D.p("outputFilePath: " + outputFile.absolutePath)
            try {
                fileOutputStream = FileOutputStream(outputFile,false)
                //fileOutputStream = openFileOutput("output.wav", Context.MODE_PRIVATE)
            } catch (e: Exception) {
                D.p("e: " + e)
            }

            extract(path)
        }
    }

    private fun showFileSelectDialog() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            ) {
                D.p("need permission")
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    requestPermissions(
                        arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                        REQUEST_CODE_PERMISSION
                    )
                }
            }
        } else {
            FilePickerUtil.showGallery(this, REQUEST_CODE_FILE_SELECT)
        }
    }

    private fun extract(path: String) {
        val extractor = MediaExtractor()
        extractor.setDataSource(path)

        val audioTrackIdx = getAudioTrackIdx(extractor)
        if (audioTrackIdx == -1) return

        val format = extractor.getTrackFormat(audioTrackIdx)
        D.p("format: " + format)

        val mime = format.getString(MediaFormat.KEY_MIME)
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        extractor.selectTrack(audioTrackIdx)

        var inputEnd = false
        var outputEnd = false
        val timeOutUs = 1000L

        // extract, decode
        while (!outputEnd) {
            if (!inputEnd) {
                val inputBufferIndex = codec.dequeueInputBuffer(timeOutUs)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputBufferIndex) as ByteBuffer

                    var sampleSize = extractor.readSampleData(inputBuffer, 0)

                    var presentationTimeUs = 0L
                    if (sampleSize < 0) {
                        inputEnd = true
                        sampleSize = 0
                    } else {
                        presentationTimeUs = extractor.sampleTime
                    }
                    val flags = if (inputEnd) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                    codec.queueInputBuffer(inputBufferIndex, 0, sampleSize, presentationTimeUs, flags)

                    if (!inputEnd) {
                        extractor.advance()
                    }

                    D.p("presentationTimeUs: " + presentationTimeUs + ", sampleSize: " + sampleSize + ", inputEnd: " + inputEnd)
                }
            }

            val bufferInfo = MediaCodec.BufferInfo()
            val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, timeOutUs)

            if (outputBufferIndex >= 0) {
                val outputBuffer = codec.getOutputBuffer(outputBufferIndex)

                val dst = ByteArray(bufferInfo.size)
                val oldPosition = outputBuffer?.position() ?: 0

                outputBuffer?.get(dst)
                outputBuffer?.position(oldPosition)

                try {
                    fileOutputStream?.write(dst)
                } catch (e: Exception) {
                    D.p("e: " + e)
                }

                codec.releaseOutputBuffer(outputBufferIndex, false)

                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    D.p("outputEnd = true")
                    outputEnd = true
                }
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                D.p("outputFormatChanged: " + codec.outputFormat)
            }
        }

        // encode
        encode()

        extractor.release()
        codec.stop()
        codec.release()

        try {
            fileOutputStream?.close()
        } catch (e: Exception) {
            D.p("e: " + e)
        }
    }

    private fun encode() {

    }

    private fun getAudioTrackIdx(extractor: MediaExtractor): Int {
        for (idx in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(idx)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime.startsWith("audio")) {
                return idx
            }
        }
        return -1
    }

    companion object {
        private const val REQUEST_CODE_PERMISSION = 300
        private const val REQUEST_CODE_FILE_SELECT = 9999
        private const val RAW_AUDIO_FILE_NAME = "rawAudio"
    }
}
