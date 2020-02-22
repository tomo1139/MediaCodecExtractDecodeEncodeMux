package develop.tomo1139.mediacodecextractdecodeencodemux.media

import android.media.*
import develop.tomo1139.mediacodecextractdecodeencodemux.util.Logger
import java.nio.ByteBuffer


class ExtractDecodeEncodeMuxer(inputFilePath: String, outputFilePath: String) {
    private val videoExtractor = MediaExtractor()
    private val videoTrackIdx: Int
    private val inputVideoFormat: MediaFormat
    private val encodeVideoFormat: MediaFormat
    private var outputVideoTrackIdx: Int = 0
    private var outputVideoFormat: MediaFormat? = null

    private val videoDecoder: MediaCodec
    private val videoEncoder: MediaCodec

    private val audioExtractor = MediaExtractor()
    private val audioTrackIdx: Int
    private val inputAudioFormat: MediaFormat
    private val encodeAudioFormat: MediaFormat
    private var outputAudioTrackIdx: Int = 0
    private var outputAudioFormat: MediaFormat? = null

    private val audioDecoder: MediaCodec
    private val audioEncoder: MediaCodec

    private val muxer: MediaMuxer

    private var isVideoExtractEnd = false
    private var isVideoDecodeEnd = false
    private var isVideoEncodeEnd = false

    private var isAudioExtractEnd = false
    private var isAudioDecodeEnd = false
    private var isAudioEncodeEnd = false

    private val isMuxEnd: Boolean
        get() = isVideoExtractEnd && isVideoDecodeEnd && isVideoEncodeEnd
                && isAudioExtractEnd && isAudioDecodeEnd && isAudioEncodeEnd

    init {
        videoExtractor.setDataSource(inputFilePath)
        videoTrackIdx = getVideoTrackIdx(videoExtractor)
        if (videoTrackIdx == -1) {
            Logger.e("video not found")
            throw RuntimeException("video not found")
        }
        videoExtractor.selectTrack(videoTrackIdx)
        inputVideoFormat = videoExtractor.getTrackFormat(videoTrackIdx)
        Logger.e("inputVideoFormat: $inputVideoFormat")

        audioExtractor.setDataSource(inputFilePath)
        audioTrackIdx = getAudioTrackIdx(audioExtractor)
        if (audioTrackIdx == -1) {
            Logger.e("audio not found")
            throw RuntimeException("audio not found")
        }
        audioExtractor.selectTrack(audioTrackIdx)
        inputAudioFormat = audioExtractor.getTrackFormat(audioTrackIdx)
        Logger.e("inputAudioFormat: $inputAudioFormat")

        val inputVideoMime = inputVideoFormat.getString(MediaFormat.KEY_MIME)
        val width = inputVideoFormat.getInteger(MediaFormat.KEY_WIDTH)
        val height = inputVideoFormat.getInteger(MediaFormat.KEY_HEIGHT)
        encodeVideoFormat = MediaFormat.createVideoFormat(inputVideoMime, width, height)
        encodeVideoFormat?.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) // TODO: チェック処理
        encodeVideoFormat?.setInteger(MediaFormat.KEY_BIT_RATE, 2000000)
        encodeVideoFormat?.setInteger(MediaFormat.KEY_FRAME_RATE, inputVideoFormat.getInteger(MediaFormat.KEY_FRAME_RATE))
        encodeVideoFormat?.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)

        Logger.e("encodeVideoFormat: $encodeVideoFormat")

        videoDecoder = MediaCodec.createDecoderByType(inputVideoMime)
        videoDecoder.configure(inputVideoFormat, null, null, 0)

        videoEncoder = MediaCodec.createEncoderByType(inputVideoMime)
        videoEncoder.configure(encodeVideoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        val inputAudioMime = inputAudioFormat.getString(MediaFormat.KEY_MIME)
        val sampleRate = inputAudioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = inputAudioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        encodeAudioFormat = MediaFormat().also {
            it.setString(MediaFormat.KEY_MIME, inputAudioMime)
            it.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            it.setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
            it.setInteger(MediaFormat.KEY_BIT_RATE, inputAudioFormat.getInteger(MediaFormat.KEY_BIT_RATE))
            it.setInteger(MediaFormat.KEY_CHANNEL_COUNT, channelCount)
        }
        Logger.e("encodeAudioFormat: $encodeAudioFormat")

        audioDecoder = MediaCodec.createDecoderByType(inputAudioMime)
        audioDecoder.configure(inputAudioFormat, null, null, 0)

        audioEncoder = MediaCodec.createEncoderByType(inputAudioMime)
        audioEncoder.configure(encodeAudioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        val videoMetaData = MediaMetadataRetriever()
        videoMetaData.setDataSource(inputFilePath)
        val degreeString = videoMetaData.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
        val videoDegree = degreeString?.toInt() ?: 0
        muxer = MediaMuxer(outputFilePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.setOrientationHint(videoDegree)
    }

    fun doExtractDecodeEncodeMux() {
        videoDecoder.start()
        videoEncoder.start()
        audioDecoder.start()
        audioEncoder.start()

        while (!isMuxEnd) {
            if (!isVideoExtractEnd) { isVideoExtractEnd = extract(videoExtractor, videoDecoder) }
            if (!isAudioExtractEnd) { isAudioExtractEnd = extract(audioExtractor, audioDecoder) }
            if (!isVideoDecodeEnd) decodeVideo()
            if (!isAudioDecodeEnd) decodeAudio()
            if (!isVideoEncodeEnd) encodeVideo()
            if (!isAudioEncodeEnd) encodeAudio()
        }

        videoExtractor.release()
        videoDecoder.stop()
        videoDecoder.release()
        videoEncoder.stop()
        videoEncoder.release()
        audioExtractor.release()
        audioDecoder.stop()
        audioDecoder.release()
        audioEncoder.stop()
        audioEncoder.release()
        muxer.stop()
        muxer.release()
    }

    private fun extract(extractor: MediaExtractor, decoder: MediaCodec): Boolean {
        var isExtractEnd = false
        val inputBufferIdx = decoder.dequeueInputBuffer(CODEC_TIMEOUT_IN_US)
        if (inputBufferIdx >= 0) {
            val inputBuffer = decoder.getInputBuffer(inputBufferIdx) as ByteBuffer
            val sampleSize = extractor.readSampleData(inputBuffer, 0)
            if (sampleSize > 0) {
                decoder.queueInputBuffer(inputBufferIdx, 0, sampleSize, extractor.sampleTime, extractor.sampleFlags)
            } else {
                Logger.e("isExtractEnd = true")
                isExtractEnd = true
                decoder.queueInputBuffer(inputBufferIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }

            if (!isExtractEnd) {
                extractor.advance()
            }
        }
        return isExtractEnd
    }

    private fun decodeVideo() {
        val decoderOutputBufferInfo = MediaCodec.BufferInfo()
        val decoderOutputBufferIdx = videoDecoder.dequeueOutputBuffer(decoderOutputBufferInfo, CODEC_TIMEOUT_IN_US)

        if (decoderOutputBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
            Logger.e("isVideoDecodeEnd = true")
            isVideoDecodeEnd = true
        }
        if (decoderOutputBufferIdx >= 0) {
            val encoderInputBufferIdx = videoEncoder.dequeueInputBuffer(CODEC_TIMEOUT_IN_US)
            if (encoderInputBufferIdx >= 0) {
                val decoderOutputBuffer = (videoDecoder.getOutputBuffer(decoderOutputBufferIdx) as ByteBuffer).duplicate()
                decoderOutputBuffer.position(decoderOutputBufferInfo.offset)
                decoderOutputBuffer.limit(decoderOutputBufferInfo.offset + decoderOutputBufferInfo.size)

                val encoderInputBuffer = videoEncoder.getInputBuffer(encoderInputBufferIdx)
                encoderInputBuffer?.position(0)
                encoderInputBuffer?.put(decoderOutputBuffer)

                val flags = if (isVideoDecodeEnd) MediaCodec.BUFFER_FLAG_END_OF_STREAM else decoderOutputBufferInfo.flags

                videoEncoder.queueInputBuffer(
                    encoderInputBufferIdx, 0,
                    decoderOutputBufferInfo.size,
                    decoderOutputBufferInfo.presentationTimeUs, flags
                )
                videoDecoder.releaseOutputBuffer(decoderOutputBufferIdx, false)
            }
        }
    }

    private fun decodeAudio() {
        val decoderOutputBufferInfo = MediaCodec.BufferInfo()
        val decoderOutputBufferIdx = audioDecoder.dequeueOutputBuffer(decoderOutputBufferInfo, CODEC_TIMEOUT_IN_US)

        if (decoderOutputBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
            Logger.e("isAudioDecodeEnd = true")
            isAudioDecodeEnd = true
        }

        if (decoderOutputBufferIdx >= 0) {
            val encoderInputBufferIdx = audioEncoder.dequeueInputBuffer(CODEC_TIMEOUT_IN_US)
            if (encoderInputBufferIdx >= 0) {
                val decoderOutputBuffer = (audioDecoder.getOutputBuffer(decoderOutputBufferIdx) as ByteBuffer).duplicate()
                decoderOutputBuffer.position(decoderOutputBufferInfo.offset)
                decoderOutputBuffer.limit(decoderOutputBufferInfo.offset + decoderOutputBufferInfo.size)

                val encoderInputBuffer = audioEncoder.getInputBuffer(encoderInputBufferIdx)
                encoderInputBuffer?.position(0)
                encoderInputBuffer?.put(decoderOutputBuffer)

                val flags = if (isAudioDecodeEnd) MediaCodec.BUFFER_FLAG_END_OF_STREAM else decoderOutputBufferInfo.flags

                audioEncoder.queueInputBuffer(
                    encoderInputBufferIdx, 0,
                    decoderOutputBufferInfo.size,
                    decoderOutputBufferInfo.presentationTimeUs, flags
                )
                audioDecoder.releaseOutputBuffer(decoderOutputBufferIdx, false)
            }
        }
    }

    private fun encodeVideo() {
        val encoderOutputBufferInfo = MediaCodec.BufferInfo()
        val encoderOutputBufferIdx = videoEncoder.dequeueOutputBuffer(encoderOutputBufferInfo, CODEC_TIMEOUT_IN_US)

        if (encoderOutputBufferIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            Logger.e("output format changed: ${videoEncoder.outputFormat}")
            outputVideoFormat = videoEncoder.outputFormat
            outputVideoFormat?.let {
                outputVideoTrackIdx = muxer.addTrack(it)
                if (outputVideoFormat != null && outputAudioFormat != null) {
                    Logger.e("muxer start")
                    muxer.start()
                    return
                }
            }
        }

        if (encoderOutputBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            videoEncoder.releaseOutputBuffer(encoderOutputBufferIdx, false)
            return
        }

        outputVideoFormat?: return
        outputAudioFormat?: return

        if (encoderOutputBufferIdx >= 0) {
            val encoderOutputBuffer = videoEncoder.getOutputBuffer(encoderOutputBufferIdx)
            if (encoderOutputBufferInfo.size > 0) {
                encoderOutputBuffer?.let {
                    muxer.writeSampleData(outputVideoTrackIdx, it, encoderOutputBufferInfo)
                    videoEncoder.releaseOutputBuffer(encoderOutputBufferIdx, false)
                }
            }
            Logger.e("presentationTimeUs: ${encoderOutputBufferInfo.presentationTimeUs}")
        }

        if (encoderOutputBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
            Logger.e("isVideoEncodeEnd = true")
            isVideoEncodeEnd = true
        }
    }

    private fun encodeAudio() {
        val encoderOutputBufferInfo = MediaCodec.BufferInfo()
        val encoderOutputBufferIdx = audioEncoder.dequeueOutputBuffer(encoderOutputBufferInfo, CODEC_TIMEOUT_IN_US)

        if (encoderOutputBufferIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            Logger.e("output format changed: ${audioEncoder.outputFormat}")
            outputAudioFormat = audioEncoder.outputFormat
            outputAudioFormat?.let {
                outputAudioTrackIdx = muxer.addTrack(it)
                if (outputVideoFormat != null && outputAudioFormat != null) {
                    Logger.e("muxer start")
                    muxer.start()
                    return
                }
            }
        }

        if (encoderOutputBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            audioEncoder.releaseOutputBuffer(encoderOutputBufferIdx, false)
            return
        }

        outputVideoFormat?: return
        outputAudioFormat?: return

        if (encoderOutputBufferIdx >= 0) {
            val encoderOutputBuffer = audioEncoder.getOutputBuffer(encoderOutputBufferIdx)
            if (encoderOutputBufferInfo.size > 0) {
                encoderOutputBuffer?.let {
                    muxer.writeSampleData(outputAudioTrackIdx, it, encoderOutputBufferInfo)
                    audioEncoder.releaseOutputBuffer(encoderOutputBufferIdx, false)
                }
            }
            Logger.e("presentationTimeUs: ${encoderOutputBufferInfo.presentationTimeUs}")
        }

        if (encoderOutputBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
            Logger.e("isAudioEncodeEnd = true")
            isAudioEncodeEnd = true
        }
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
        private const val CODEC_TIMEOUT_IN_US = 10000L
    }
}