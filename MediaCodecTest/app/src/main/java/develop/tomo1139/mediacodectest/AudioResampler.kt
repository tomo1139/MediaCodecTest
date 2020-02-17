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
    private val videoMetaData = MediaMetadataRetriever()
    private val videoDegree: Int
    private val muxer: MediaMuxer


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
        videoMetaData.setDataSource(inputFilePath)
        val degreeString = videoMetaData.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
        videoDegree = degreeString?.toInt() ?: 0


        val videoOutputFile = File(workingFilesDir, ENCODED_VIDEO_FILE_NAME)
        if (videoOutputFile.exists()) {
            videoOutputFile.delete()
        }
        muxer = MediaMuxer(videoOutputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.setOrientationHint(videoDegree)
    }

    fun execute() {
        audioDecoder.configure(inputAudioFormat, null, null, 0)
        audioDecoder.start()

        audioExtractor.selectTrack(audioTrackIdx)

        var inputEnd = false
        var outputEnd = false

        while (!outputEnd) {
            if (!inputEnd) {
                val inputBufferIndex = audioDecoder.dequeueInputBuffer(CODEC_TIMEOUT_IN_US)
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
            val audioOutputBufferIndex = audioDecoder.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_IN_US)

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
        createResampledMovie()
        //audioEncodeMux()

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
        videoExtractor.selectTrack(videoTrackIdx)

        val outputVideoTrackIdx = muxer.addTrack(inputVideoFormat)

        val channelCount = inputAudioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val sampleRate = inputAudioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)

        var videoEnd = false

        val tempBuffer = ByteBuffer.allocate(1024 * 1024)

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

        val audioInputBuffers = audioEncoder.inputBuffers
        val audioOutputBuffers = audioEncoder.outputBuffers
        val audioOutputBufferInfo = MediaCodec.BufferInfo()
        val audioTempBuffer = ByteArray(1024*1024)
        var presentationTimeUs = 0L
        var audioTrackIdx = 0
        var totalBytesRead = 0

        var audioInputEnd = false
        var audioOutputEnd = false

        val inputFile = File(workingFilesDir, RAW_AUDIO_FILE_NAME)
        val fileInputStream = FileInputStream(inputFile)

        var isMuxerStarted = false

        while (!videoEnd || !audioOutputEnd) {

            while (!videoEnd && isMuxerStarted) {
                val sampleSize = videoExtractor.readSampleData(tempBuffer, 0)
                if (sampleSize < 0) {
                    D.p("videoEnd = true")
                    videoEnd = true
                } else {
                    val bufferInfo = MediaCodec.BufferInfo()
                    bufferInfo.size = sampleSize
                    bufferInfo.presentationTimeUs = videoExtractor.sampleTime
                    bufferInfo.flags = videoExtractor.sampleFlags
                    muxer.writeSampleData(outputVideoTrackIdx, tempBuffer, bufferInfo)
                }

                if (!videoEnd) {
                    videoExtractor.advance()
                }
                D.p("sampleTime: " + videoExtractor.sampleTime)
            }

            while (!audioOutputEnd) {
                if (!audioInputEnd) {
                    var inputBufferIndex = 0

                    while (inputBufferIndex != -1 && !audioInputEnd) {
                        inputBufferIndex = audioEncoder.dequeueInputBuffer(CODEC_TIMEOUT_IN_US)
                        if (inputBufferIndex >= 0) {
                            val dstBuffer = audioInputBuffers[inputBufferIndex]
                            dstBuffer.clear()

                            val bytesRead = fileInputStream.read(audioTempBuffer, 0, dstBuffer.limit())
                            if (bytesRead == -1) {
                                audioInputEnd = true
                                D.p("audioInputEnd = true")
                                audioEncoder.queueInputBuffer(inputBufferIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            } else {
                                totalBytesRead += bytesRead
                                dstBuffer.put(audioTempBuffer, 0, bytesRead)
                                audioEncoder.queueInputBuffer(inputBufferIndex, 0, bytesRead, presentationTimeUs, 0)
                                presentationTimeUs = 1000000L  * (totalBytesRead / (2 * channelCount)) / sampleRate
                                D.p("presentationTimeUs: " + presentationTimeUs)
                            }
                        }
                    }
                }

                if (!audioOutputEnd) {
                    var audioOutputBufferIndex = 0

                    while (audioOutputBufferIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {
                        audioOutputBufferIndex = audioEncoder.dequeueOutputBuffer(audioOutputBufferInfo, CODEC_TIMEOUT_IN_US)
                        if (audioOutputBufferIndex >= 0) {
                            val encodedData = audioOutputBuffers[audioOutputBufferIndex]
                            encodedData.position(audioOutputBufferInfo.offset)
                            encodedData.limit(audioOutputBufferInfo.offset + audioOutputBufferInfo.size)

                            if ((audioOutputBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 && audioOutputBufferInfo.size != 0) {
                                audioEncoder.releaseOutputBuffer(audioOutputBufferIndex, false)
                            } else {
                                muxer.writeSampleData(audioTrackIdx, audioOutputBuffers[audioOutputBufferIndex], audioOutputBufferInfo)
                                audioEncoder.releaseOutputBuffer(audioOutputBufferIndex, false)
                            }
                        } else if (audioOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            D.p("output format changed: " + audioEncoder.outputFormat)
                            outputAudioFormat = audioEncoder.outputFormat
                            audioTrackIdx = muxer.addTrack(outputAudioFormat)
                            muxer.start()
                            isMuxerStarted = true
                        }

                        if (audioOutputBufferInfo.flags == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
                            D.p("audioOutputEnd = true")
                            audioOutputEnd = true
                        }
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

    private fun audioEncodeMux() {
        val inputFile = File(workingFilesDir, RAW_AUDIO_FILE_NAME)
        val fileInputStream = FileInputStream(inputFile)

        val outputFile = File(workingFilesDir, ENCODED_AUDIO_FILE_NAME)
        if (outputFile.exists()) {
            outputFile.delete()
        }

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

        val audioInputBuffers = audioEncoder.inputBuffers
        val audioOutputBuffers = audioEncoder.outputBuffers
        val audioOutputBufferInfo = MediaCodec.BufferInfo()
        val audioTempBuffer = ByteArray(1024*1024)
        var presentationTimeUs = 0L
        var audioTrackIdx = 0
        var totalBytesRead = 0

        var inputEnd = false
        var outputEnd = false

        while (!outputEnd) {
            if (!inputEnd) {
                var inputBufferIndex = 0

                while (inputBufferIndex != -1 && !inputEnd) {
                    inputBufferIndex = audioEncoder.dequeueInputBuffer(CODEC_TIMEOUT_IN_US)
                    if (inputBufferIndex >= 0) {
                        val dstBuffer = audioInputBuffers[inputBufferIndex]
                        dstBuffer.clear()

                        val bytesRead = fileInputStream.read(audioTempBuffer, 0, dstBuffer.limit())
                        if (bytesRead == -1) {
                            inputEnd = true
                            audioEncoder.queueInputBuffer(inputBufferIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            totalBytesRead += bytesRead
                            dstBuffer.put(audioTempBuffer, 0, bytesRead)
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
                    audioOutputBufferIndex = audioEncoder.dequeueOutputBuffer(audioOutputBufferInfo, CODEC_TIMEOUT_IN_US)
                    if (audioOutputBufferIndex >= 0) {
                        val encodedData = audioOutputBuffers[audioOutputBufferIndex]
                        encodedData.position(audioOutputBufferInfo.offset)
                        encodedData.limit(audioOutputBufferInfo.offset + audioOutputBufferInfo.size)

                        if ((audioOutputBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 && audioOutputBufferInfo.size != 0) {
                            audioEncoder.releaseOutputBuffer(audioOutputBufferIndex, false)
                        } else {
                            muxer.writeSampleData(audioTrackIdx, audioOutputBuffers[audioOutputBufferIndex], audioOutputBufferInfo)
                            audioEncoder.releaseOutputBuffer(audioOutputBufferIndex, false)
                        }
                    } else if (audioOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        D.p("output format changed: " + audioEncoder.outputFormat)
                        outputAudioFormat = audioEncoder.outputFormat
                        audioTrackIdx = muxer.addTrack(outputAudioFormat)
                        muxer.start()
                    }

                    if (audioOutputBufferInfo.flags == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
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
        private const val CODEC_TIMEOUT_IN_US = 5000L
        private const val RAW_AUDIO_FILE_NAME = "rawAudio"
        private const val ENCODED_AUDIO_FILE_NAME = "encodedAudio.m4a"
        private const val ENCODED_VIDEO_FILE_NAME = "encodedVideo.mp4"
    }
}