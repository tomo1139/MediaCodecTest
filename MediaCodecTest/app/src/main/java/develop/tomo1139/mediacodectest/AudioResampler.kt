package develop.tomo1139.mediacodectest

import android.content.Context
import android.media.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.RuntimeException
import java.nio.ByteBuffer

class AudioResampler(context: Context, inputFilePath: String) {

    private var rawAudioFileOutputStream: FileOutputStream? = null

    private val workingFilesDir = context.getExternalFilesDir(null)

    private val audioExtractor = MediaExtractor()
    private val audioTrackIdx: Int
    private val inputAudioFormat: MediaFormat
    private val inputAudioMime: String
    private val audioDecoder: MediaCodec

    private val videoExtractor = MediaExtractor()
    private val videoTrackIdx: Int
    private val inputVideoFormat: MediaFormat
    private val inputVideoMime: String
    private val videoDecoder: MediaCodec

    init {
        val outputFile = File(workingFilesDir, RAW_AUDIO_FILE_NAME)
        if (outputFile.exists()) {
            outputFile.delete()
        }
        //D.p("outputFilePath: " + outputFile.absolutePath)
        try {
            rawAudioFileOutputStream = FileOutputStream(outputFile,false)
            //rawAudioFileOutputStream = openFileOutput("output.wav", Context.MODE_PRIVATE) // TODO
        } catch (e: Exception) {
            D.p("e: " + e)
        }

        audioExtractor.setDataSource(inputFilePath)
        audioTrackIdx = getAudioTrackIdx(audioExtractor)
        if (audioTrackIdx == -1) {
            D.p("audio not found")
            throw RuntimeException("audio not found")
        }
        inputAudioFormat = audioExtractor.getTrackFormat(audioTrackIdx)
        D.p("inputAudioFormat: " + inputAudioFormat)
        inputAudioMime = inputAudioFormat.getString(MediaFormat.KEY_MIME) ?: ""
        audioDecoder = MediaCodec.createDecoderByType(inputAudioMime)

        videoExtractor.setDataSource(inputFilePath)
        videoTrackIdx = getVideoTrackIdx(videoExtractor)
        if (videoTrackIdx == -1) {
            D.p("video not found")
            throw RuntimeException("video not found")
        }
        inputVideoFormat = videoExtractor.getTrackFormat(videoTrackIdx)
        D.p("inputVideoFormat: " + inputVideoFormat)
        inputVideoMime = inputVideoFormat.getString(MediaFormat.KEY_MIME) ?: ""
        videoDecoder = MediaCodec.createDecoderByType(inputVideoMime)
    }

    fun execute() {
        audioDecoder.configure(inputAudioFormat, null, null, 0)
        audioDecoder.start()

        audioExtractor.selectTrack(audioTrackIdx)

        var inputEnd = false
        var outputEnd = false

        while (!outputEnd) {
            if (!inputEnd) {
                val inputBufferIndex = audioDecoder.dequeueInputBuffer(CODEC_TIMEOUT_IN_MS)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = audioDecoder.getInputBuffer(inputBufferIndex) as ByteBuffer

                    var sampleSize = audioExtractor.readSampleData(inputBuffer, 0)

                    var presentationTimeUs = 0L
                    if (sampleSize < 0) {
                        inputEnd = true
                        sampleSize = 0
                    } else {
                        presentationTimeUs = audioExtractor.sampleTime
                    }
                    val flags = if (inputEnd) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                    audioDecoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, presentationTimeUs, flags)

                    if (!inputEnd) {
                        audioExtractor.advance()
                    }

                    D.p("presentationTimeUs: " + presentationTimeUs + ", sampleSize: " + sampleSize + ", inputEnd: " + inputEnd)
                }
            }

            val bufferInfo = MediaCodec.BufferInfo()
            val audioOutputBufferIndex = audioDecoder.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_IN_MS)

            if (audioOutputBufferIndex >= 0) {
                val outputBuffer = audioDecoder.getOutputBuffer(audioOutputBufferIndex)

                val dst = ByteArray(bufferInfo.size)
                val oldPosition = outputBuffer?.position() ?: 0
                outputBuffer?.get(dst)
                outputBuffer?.position(oldPosition)

                try {
                    rawAudioFileOutputStream?.write(dst)
                } catch (e: Exception) {
                    D.p("e: " + e)
                }

                audioDecoder.releaseOutputBuffer(audioOutputBufferIndex, false)

                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    D.p("outputEnd = true")
                    outputEnd = true
                }
            } else if (audioOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                D.p("outputFormatChanged: " + audioDecoder.outputFormat)
            }
        }

        // TODO: Resample raw audio

        // encode & mux audio file
        //audioEncodeMux()
        createResampledMovie()

        audioExtractor.release()
        videoExtractor.release()
        audioDecoder.stop()
        audioDecoder.release()

        try {
            rawAudioFileOutputStream?.close()
        } catch (e: Exception) {
            D.p("e: " + e)
        }
    }

    private fun createResampledMovie() {
        videoDecoder.configure(inputVideoFormat, null, null, 0)
        videoDecoder.start()

        videoExtractor.selectTrack(videoTrackIdx)

        var outputVideoFormat = MediaFormat().also {
            val frameRate = inputVideoFormat.getInteger(MediaFormat.KEY_FRAME_RATE)
            val width = inputVideoFormat.getInteger(MediaFormat.KEY_WIDTH)
            val height = inputVideoFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val bitRate = 0.1f * frameRate * width * height

            D.p("bitRate: " + bitRate / 1024f + ", [kbps]")

            it.setString(MediaFormat.KEY_MIME, inputVideoFormat.getString(MediaFormat.KEY_MIME))
            it.setInteger(MediaFormat.KEY_LEVEL, inputVideoFormat.getInteger(MediaFormat.KEY_LEVEL))
            it.setInteger(MediaFormat.KEY_WIDTH, width)
            it.setInteger(MediaFormat.KEY_HEIGHT, height)
            it.setInteger(MediaFormat.KEY_BIT_RATE, bitRate.toInt())
            it.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            it.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            it.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        }

        val outputFile = File(workingFilesDir, ENCODED_VIDEO_FILE_NAME)
        if (outputFile.exists()) {
            outputFile.delete()
        }
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var outputVideoTrackIdx = 0
        val videoEncoder = MediaCodec.createEncoderByType("video/avc")
        videoEncoder.configure(outputVideoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        videoEncoder.start()

        var decodeEnd = false
        var encodeEnd = false
        var muxEnd = false

        while (!muxEnd) {
            if (!decodeEnd) {
                val inputBufferIndex = videoDecoder.dequeueInputBuffer(CODEC_TIMEOUT_IN_MS)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = videoDecoder.getInputBuffer(inputBufferIndex) as ByteBuffer

                    var sampleSize = videoExtractor.readSampleData(inputBuffer, 0)
                    var presentationTimeUs = 0L
                    if (sampleSize < 0) {
                        D.p("decodeEnd = true")
                        decodeEnd = true
                        sampleSize = 0
                    } else {
                        presentationTimeUs = videoExtractor.sampleTime
                    }
                    val flags = if (decodeEnd) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                    videoDecoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, presentationTimeUs, flags)

                    if (!decodeEnd) {
                        videoExtractor.advance()
                    }
                }
            }

            if (!encodeEnd) {
                val bufferInfo = MediaCodec.BufferInfo()
                val videoOutputBufferIndex = videoDecoder.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_IN_MS)

                if (videoOutputBufferIndex >= 0) {
                    val outputBuffer = videoDecoder.getOutputBuffer(videoOutputBufferIndex)

                    val dst = ByteArray(bufferInfo.size)
                    val oldPosition = outputBuffer?.position() ?: 0
                    outputBuffer?.get(dst)
                    outputBuffer?.position(oldPosition)

                    val inputBufferIndex = videoEncoder.dequeueInputBuffer(CODEC_TIMEOUT_IN_MS)
                    if (inputBufferIndex >= 0) {
                        val dstBuffer = videoEncoder.getInputBuffer(inputBufferIndex)
                        dstBuffer?.clear()

                        val bytesRead = dst.size
                        if (bytesRead == -1) {
                            D.p("encodeEnd: true")
                            encodeEnd = true
                            videoEncoder.queueInputBuffer(inputBufferIndex, 0, 0, bufferInfo.presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            dstBuffer?.put(dst, 0, bytesRead)
                            videoEncoder.queueInputBuffer(inputBufferIndex, 0, bytesRead, bufferInfo.presentationTimeUs, 0)
                        }
                    }

                    videoDecoder.releaseOutputBuffer(videoOutputBufferIndex, false)
                }
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    val inputBufferIndex = videoEncoder.dequeueInputBuffer(CODEC_TIMEOUT_IN_MS)
                    if (inputBufferIndex >= 0) {
                        D.p("encodeEnd: true")
                        encodeEnd = true
                        videoEncoder.queueInputBuffer(inputBufferIndex, 0, 0, bufferInfo.presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    }
                }
            }

            if (!muxEnd) {
                var videoOutputBufferIndex = 0
                val bufferInfo = MediaCodec.BufferInfo()

                while (videoOutputBufferIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {

                    videoOutputBufferIndex = videoEncoder.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_IN_MS)
                    if (videoOutputBufferIndex >= 0) {
                        val encodedData = videoEncoder.getOutputBuffer(videoOutputBufferIndex)

                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            videoEncoder.releaseOutputBuffer(videoOutputBufferIndex, false)
                        } else {
                            val outputBuffer = videoEncoder.getOutputBuffer(videoOutputBufferIndex)
                            if (bufferInfo.size != 0) {
                                outputBuffer?.let {
                                    muxer.writeSampleData(outputVideoTrackIdx, it, bufferInfo)
                                }
                            }

                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                D.p("muxEnd = true")
                                muxEnd = true
                            }
                            videoEncoder.releaseOutputBuffer(videoOutputBufferIndex, false)
                        }
                    } else if (videoOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        D.p("output format changed: " + videoEncoder.outputFormat)
                        outputVideoFormat = videoEncoder.outputFormat
                        outputVideoTrackIdx = muxer.addTrack(outputVideoFormat)
                        muxer.start()
                    }
                }
            }
        }

        videoDecoder.stop()
        videoDecoder.release()
        videoEncoder.stop()
        videoEncoder.release()
        muxer.stop()
        muxer.release()
    }

    private fun audioEncodeMux() {
        val inputFile = File(workingFilesDir, RAW_AUDIO_FILE_NAME)
        val fileInputStream = FileInputStream(inputFile)

        val outputFile = File(workingFilesDir, ENCODED_AUDIO_FILE_NAME)
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
        val audioEncoder = MediaCodec.createEncoderByType("audio/mp4a-latm")
        audioEncoder.configure(outputAudioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        audioEncoder.start()

        val inputBuffers = audioEncoder.inputBuffers
        val outputBuffers = audioEncoder.outputBuffers
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
                    inputBufferIndex = audioEncoder.dequeueInputBuffer(CODEC_TIMEOUT_IN_MS)
                    if (inputBufferIndex >= 0) {
                        val dstBuffer = inputBuffers[inputBufferIndex]
                        dstBuffer.clear()

                        val bytesRead = fileInputStream.read(tempBuffer, 0, dstBuffer.limit())
                        if (bytesRead == -1) {
                            inputEnd = true
                            audioEncoder.queueInputBuffer(inputBufferIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            totalBytesRead += bytesRead
                            dstBuffer.put(tempBuffer, 0, bytesRead)
                            audioEncoder.queueInputBuffer(inputBufferIndex, 0, bytesRead, presentationTimeUs, 0)
                            presentationTimeUs = 1000000L  * (totalBytesRead / (2 * channelCount)) / sampleRate
                            D.p("presentationTimeUs: " + presentationTimeUs)
                        }
                    }
                }
            }

            if (!outputEnd) {
                var audioOutputBufferIndex = 0

                while (audioOutputBufferIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    audioOutputBufferIndex = audioEncoder.dequeueOutputBuffer(outputBufferInfo, CODEC_TIMEOUT_IN_MS)
                    if (audioOutputBufferIndex >= 0) {
                        val encodedData = outputBuffers[audioOutputBufferIndex]
                        encodedData.position(outputBufferInfo.offset)
                        encodedData.limit(outputBufferInfo.offset + outputBufferInfo.size)

                        if ((outputBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 && outputBufferInfo.size != 0) {
                            audioEncoder.releaseOutputBuffer(audioOutputBufferIndex, false)
                        } else {
                            muxer.writeSampleData(audioTrackIdx, outputBuffers[audioOutputBufferIndex], outputBufferInfo)
                            audioEncoder.releaseOutputBuffer(audioOutputBufferIndex, false)
                        }
                    } else if (audioOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        D.p("output format changed: " + audioEncoder.outputFormat)
                        outputAudioFormat = audioEncoder.outputFormat
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

        audioEncoder.stop()
        audioEncoder.release()
        fileInputStream.close()
        muxer.stop()
        muxer.release()
    }

    private fun getAudioTrackIdx(extractor: MediaExtractor): Int {
        for (idx in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(idx)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio") == true) {
                return idx
            }
        }
        return -1
    }

    private fun getVideoTrackIdx(extractor: MediaExtractor): Int {
        for (idx in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(idx)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("video") == true) {
                return idx
            }
        }
        return -1
    }

    companion object {
        private const val CODEC_TIMEOUT_IN_MS = 5000L
        private const val RAW_AUDIO_FILE_NAME = "rawAudio"
        private const val ENCODED_AUDIO_FILE_NAME = "encodedAudio.m4a"
        private const val ENCODED_VIDEO_FILE_NAME = "encodedVideo.mp4"
    }
}