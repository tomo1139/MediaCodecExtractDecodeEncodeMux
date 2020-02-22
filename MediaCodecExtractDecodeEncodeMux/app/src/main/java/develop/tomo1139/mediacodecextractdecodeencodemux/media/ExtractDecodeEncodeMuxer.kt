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
        encodeVideoFormat = MediaFormat.createVideoFormat(inputVideoMime, width, height).also {
            it.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            it.setInteger(MediaFormat.KEY_BIT_RATE, 2000000)
            it.setInteger(MediaFormat.KEY_FRAME_RATE, inputVideoFormat.getInteger(MediaFormat.KEY_FRAME_RATE))
            it.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
        }
        Logger.e("encodeVideoFormat: $encodeVideoFormat")

        videoDecoder = MediaCodec.createDecoderByType(inputVideoMime)
        videoDecoder.configure(inputVideoFormat, null, null, 0)

        videoEncoder = MediaCodec.createEncoderByType(inputVideoMime)
        videoEncoder.configure(encodeVideoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        val inputAudioMime = inputAudioFormat.getString(MediaFormat.KEY_MIME)
        val sampleRate = inputAudioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = inputAudioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        encodeAudioFormat = MediaFormat.createAudioFormat(inputAudioMime, sampleRate, channelCount).also {
            it.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            it.setInteger(MediaFormat.KEY_BIT_RATE, inputAudioFormat.getInteger(MediaFormat.KEY_BIT_RATE))
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
            if (!isVideoDecodeEnd) { isVideoDecodeEnd = decode(videoDecoder, videoEncoder) }
            if (!isAudioDecodeEnd) { isAudioDecodeEnd = decode(audioDecoder, audioEncoder) }
            if (!isVideoEncodeEnd) {
                isVideoEncodeEnd = encode(videoEncoder, {
                    outputVideoFormat = it
                    outputVideoTrackIdx = muxer.addTrack(it)
                }, { outputBuffer, outputBufferInfo ->
                    muxer.writeSampleData(outputVideoTrackIdx, outputBuffer, outputBufferInfo)
                })
            }
            if (!isAudioEncodeEnd) {
                isAudioEncodeEnd = encode(audioEncoder, {
                    outputAudioFormat = it
                    outputAudioTrackIdx = muxer.addTrack(it)
                }, { outputBuffer, outputBufferInfo ->
                    muxer.writeSampleData(outputAudioTrackIdx, outputBuffer, outputBufferInfo)
                })
            }
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

    private fun decode(decoder: MediaCodec, encoder: MediaCodec): Boolean {
        var isDecodeEnd = false
        val decoderOutputBufferInfo = MediaCodec.BufferInfo()
        val decoderOutputBufferIdx = decoder.dequeueOutputBuffer(decoderOutputBufferInfo, CODEC_TIMEOUT_IN_US)

        if (decoderOutputBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
            Logger.e("isDecodeEnd = true")
            isDecodeEnd = true
        }
        if (decoderOutputBufferIdx >= 0) {
            val encoderInputBufferIdx = encoder.dequeueInputBuffer(CODEC_TIMEOUT_IN_US)
            if (encoderInputBufferIdx >= 0) {
                val decoderOutputBuffer = (decoder.getOutputBuffer(decoderOutputBufferIdx) as ByteBuffer).duplicate()
                decoderOutputBuffer.position(decoderOutputBufferInfo.offset)
                decoderOutputBuffer.limit(decoderOutputBufferInfo.offset + decoderOutputBufferInfo.size)

                val encoderInputBuffer = encoder.getInputBuffer(encoderInputBufferIdx)
                encoderInputBuffer?.position(0)
                encoderInputBuffer?.put(decoderOutputBuffer)

                val flags = if (isDecodeEnd) MediaCodec.BUFFER_FLAG_END_OF_STREAM else decoderOutputBufferInfo.flags

                encoder.queueInputBuffer(
                    encoderInputBufferIdx, 0,
                    decoderOutputBufferInfo.size,
                    decoderOutputBufferInfo.presentationTimeUs, flags
                )
                decoder.releaseOutputBuffer(decoderOutputBufferIdx, false)
            }
        }
        return isDecodeEnd
    }

    private fun encode(
        encoder: MediaCodec,
        onOutputFormatChaned: (outputFormat: MediaFormat) -> Unit,
        writeEncodedData: (outputBuffer: ByteBuffer, outputBufferInfo: MediaCodec.BufferInfo) -> Unit
    ): Boolean {
        var isEncodeEnd = false
        val encoderOutputBufferInfo = MediaCodec.BufferInfo()
        val encoderOutputBufferIdx = encoder.dequeueOutputBuffer(encoderOutputBufferInfo, CODEC_TIMEOUT_IN_US)

        if (encoderOutputBufferIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            Logger.e("output format changed: ${encoder.outputFormat}")
            onOutputFormatChaned(encoder.outputFormat)
            if (outputVideoFormat != null && outputAudioFormat != null) {
                Logger.e("muxer start")
                muxer.start()
            }
            return isEncodeEnd
        }

        if (encoderOutputBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            encoder.releaseOutputBuffer(encoderOutputBufferIdx, false)
            return isEncodeEnd
        }

        outputVideoFormat?: return isEncodeEnd
        outputAudioFormat?: return isEncodeEnd

        if (encoderOutputBufferIdx >= 0) {
            val encoderOutputBuffer = encoder.getOutputBuffer(encoderOutputBufferIdx)
            if (encoderOutputBufferInfo.size > 0) {
                encoderOutputBuffer?.let {
                    writeEncodedData(it, encoderOutputBufferInfo)
                    encoder.releaseOutputBuffer(encoderOutputBufferIdx, false)
                }
            }
            Logger.e("presentationTimeUs: ${encoderOutputBufferInfo.presentationTimeUs}, encoder: ${encoder.codecInfo.name}")
        }

        if (encoderOutputBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
            Logger.e("isEncodeEnd = true")
            isEncodeEnd = true
        }
        return isEncodeEnd
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