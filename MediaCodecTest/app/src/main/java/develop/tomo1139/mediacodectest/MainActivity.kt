package develop.tomo1139.mediacodectest

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.*
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.databinding.DataBindingUtil
import develop.tomo1139.mediacodectest.databinding.ActivityMainBinding
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
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
            if (outputFile.exists()) {
                outputFile.delete()
            }
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
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        audioExtractor.setDataSource(path)

        val audioTrackIdx = getAudioTrackIdx(audioExtractor)
        if (audioTrackIdx == -1) return

        val videoTrackIdx = getVideoTrackIdx(videoExtractor)
        if (videoTrackIdx == -1) return

        val inputAudioFormat = audioExtractor.getTrackFormat(audioTrackIdx)
        D.p("inputAudioFormat: " + inputAudioFormat)

        val inputVideoFormat = videoExtractor.getTrackFormat(videoTrackIdx)
        D.p("inputVideoFormat: " + inputVideoFormat)

        val audioMime = inputAudioFormat.getString(MediaFormat.KEY_MIME)
        val audioCodec = MediaCodec.createDecoderByType(audioMime)
        audioCodec.configure(inputAudioFormat, null, null, 0)
        audioCodec.start()

        audioExtractor.selectTrack(audioTrackIdx)

        var inputEnd = false
        var outputEnd = false
        val timeOutUs = 1000L

        // decode
        while (!outputEnd) {
            if (!inputEnd) {
                val inputBufferIndex = audioCodec.dequeueInputBuffer(timeOutUs)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = audioCodec.getInputBuffer(inputBufferIndex) as ByteBuffer

                    var sampleSize = audioExtractor.readSampleData(inputBuffer, 0)

                    var presentationTimeUs = 0L
                    if (sampleSize < 0) {
                        inputEnd = true
                        sampleSize = 0
                    } else {
                        presentationTimeUs = audioExtractor.sampleTime
                    }
                    val flags = if (inputEnd) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                    audioCodec.queueInputBuffer(inputBufferIndex, 0, sampleSize, presentationTimeUs, flags)

                    if (!inputEnd) {
                        audioExtractor.advance()
                    }

                    D.p("presentationTimeUs: " + presentationTimeUs + ", sampleSize: " + sampleSize + ", inputEnd: " + inputEnd)
                }
            }

            val bufferInfo = MediaCodec.BufferInfo()
            val outputBufferIndex = audioCodec.dequeueOutputBuffer(bufferInfo, timeOutUs)

            if (outputBufferIndex >= 0) {
                val outputBuffer = audioCodec.getOutputBuffer(outputBufferIndex)

                val dst = ByteArray(bufferInfo.size)
                val oldPosition = outputBuffer?.position() ?: 0

                outputBuffer?.get(dst)
                outputBuffer?.position(oldPosition)

                try {
                    fileOutputStream?.write(dst)
                } catch (e: Exception) {
                    D.p("e: " + e)
                }

                audioCodec.releaseOutputBuffer(outputBufferIndex, false)

                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    D.p("outputEnd = true")
                    outputEnd = true
                }
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                D.p("outputFormatChanged: " + audioCodec.outputFormat)
            }
        }

        // encode & mux audio file
        audioEncodeMux(inputAudioFormat)

        audioExtractor.release()
        videoExtractor.release()
        audioCodec.stop()
        audioCodec.release()

        try {
            fileOutputStream?.close()
        } catch (e: Exception) {
            D.p("e: " + e)
        }
    }

    private fun audioEncodeMux(inputAudioFormat: MediaFormat) {
        val inputFilePath = getExternalFilesDir(null)
        val inputFile = File(inputFilePath, RAW_AUDIO_FILE_NAME)
        val fileInputStream = FileInputStream(inputFile)

        val outputFilePath = getExternalFilesDir(null)
        val outputFile = File(outputFilePath, ENCODED_AUDIO_FILE_NAME)
        if (outputFile.exists()) {
            outputFile.delete()
        }

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val channelCount = inputAudioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val sampleRate = inputAudioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var outputAudioFormat = MediaFormat().also {
            it.setString(MediaFormat.KEY_MIME, "audio/mp4a-latm")
            it.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            it.setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
            it.setInteger(MediaFormat.KEY_BIT_RATE, inputAudioFormat.getInteger(MediaFormat.KEY_BIT_RATE))
            it.setInteger(MediaFormat.KEY_CHANNEL_COUNT, channelCount)
        }
        val codec = MediaCodec.createEncoderByType("audio/mp4a-latm")
        codec.configure(outputAudioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val inputBuffers = codec.inputBuffers
        val outputBuffers = codec.outputBuffers
        val outputBufferInfo = MediaCodec.BufferInfo()
        val tempBuffer = ByteArray(1024*1024)
        var presentationTimeUs = 0L
        var audioTrackIdx = 0
        var totalBytesRead = 0

        var inputEnd = false
        var outputEnd = false

        while (!outputEnd) {
            if (!inputEnd) {
                var inputBufferIndex = 0

                while (inputBufferIndex != -1 && !inputEnd) {
                    inputBufferIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_IN_MS)
                    if (inputBufferIndex >= 0) {
                        val dstBuffer = inputBuffers[inputBufferIndex]
                        dstBuffer.clear()

                        val bytesRead = fileInputStream.read(tempBuffer, 0, dstBuffer.limit())
                        if (bytesRead == -1) {
                            inputEnd = true
                            codec.queueInputBuffer(inputBufferIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            totalBytesRead += bytesRead
                            dstBuffer.put(tempBuffer, 0, bytesRead)
                            codec.queueInputBuffer(inputBufferIndex, 0, bytesRead, presentationTimeUs, 0)
                            presentationTimeUs = 1000000L  * (totalBytesRead / (2 * channelCount)) / sampleRate
                        }
                    }
                }
            }

            if (!outputEnd) {
                var outputBufferIndex = 0

                while (outputBufferIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    outputBufferIndex = codec.dequeueOutputBuffer(outputBufferInfo, CODEC_TIMEOUT_IN_MS)
                    if (outputBufferIndex >= 0) {
                        val encodedData = outputBuffers[outputBufferIndex]
                        encodedData.position(outputBufferInfo.offset)
                        encodedData.limit(outputBufferInfo.offset + outputBufferInfo.size)

                        if ((outputBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 && outputBufferInfo.size != 0) {
                            codec.releaseOutputBuffer(outputBufferIndex, false)
                        } else {
                            muxer.writeSampleData(audioTrackIdx, outputBuffers[outputBufferIndex], outputBufferInfo)
                            codec.releaseOutputBuffer(outputBufferIndex, false)
                        }
                    } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        D.p("output format changed: " + codec.outputFormat)
                        outputAudioFormat = codec.outputFormat
                        audioTrackIdx = muxer.addTrack(outputAudioFormat)
                        muxer.start()
                    }

                    if (outputBufferInfo.flags == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
                        D.p("outputEnd = true")
                        outputEnd = true
                    }
                }
            }
        }

        muxer.stop()
        muxer.release()
        codec.stop()
        codec.release()
        fileInputStream.close()
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

    private fun getVideoTrackIdx(extractor: MediaExtractor): Int {
        for (idx in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(idx)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime.startsWith("video")) {
                return idx
            }
        }
        return -1
    }

    companion object {
        private const val CODEC_TIMEOUT_IN_MS = 5000L
        private const val REQUEST_CODE_PERMISSION = 300
        private const val REQUEST_CODE_FILE_SELECT = 9999
        private const val RAW_AUDIO_FILE_NAME = "rawAudio"
        private const val ENCODED_AUDIO_FILE_NAME = "encodedAudio.m4a"
    }
}
