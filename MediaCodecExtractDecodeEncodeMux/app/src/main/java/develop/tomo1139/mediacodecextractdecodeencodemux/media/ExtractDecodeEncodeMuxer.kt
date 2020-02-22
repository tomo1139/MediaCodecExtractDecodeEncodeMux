package develop.tomo1139.mediacodecextractdecodeencodemux.media

import android.media.*
import develop.tomo1139.mediacodecextractdecodeencodemux.util.Logger
import java.nio.ByteBuffer


class ExtractDecodeEncodeMuxer(inputFilePath: String, outputFilePath: String) {
    private val videoExtractor = MediaExtractor()
    private val videoTrackIdx: Int
    private val inputVideoFormat: MediaFormat
    private var outputVideoFormat: MediaFormat? = null
    private var outputVideoTrackIdx: Int = 0

    private val videoDecoder: MediaCodec
    private val videoEncoder: MediaCodec

    private val muxer: MediaMuxer

    private var isVideoExtractEnd = false
    private var isVideoDecodeEnd = false
    private var isVideoEncodeEnd = false

    private val isMuxEnd: Boolean
        get() = isVideoExtractEnd && isVideoDecodeEnd && isVideoEncodeEnd

    init {
        videoExtractor.setDataSource(inputFilePath)
        videoTrackIdx = getVideoTrackIdx(videoExtractor)
        if (videoTrackIdx == -1) {
            Logger.e("video not found")
            throw RuntimeException("video not found")
        }
        inputVideoFormat = videoExtractor.getTrackFormat(videoTrackIdx)
        Logger.e("inputVideoFormat: $inputVideoFormat")

        val inputVideoMime = inputVideoFormat.getString(MediaFormat.KEY_MIME)
        val width = inputVideoFormat.getInteger(MediaFormat.KEY_WIDTH)
        val height = inputVideoFormat.getInteger(MediaFormat.KEY_HEIGHT)
        outputVideoFormat = MediaFormat.createVideoFormat(inputVideoMime, width, height)
        outputVideoFormat?.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) // TODO: チェック処理
        outputVideoFormat?.setInteger(MediaFormat.KEY_BIT_RATE, 2000000)
        outputVideoFormat?.setInteger(MediaFormat.KEY_FRAME_RATE, inputVideoFormat.getInteger(MediaFormat.KEY_FRAME_RATE))
        outputVideoFormat?.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)

        Logger.e("outputVideoFormat: $outputVideoFormat")

        videoDecoder = MediaCodec.createDecoderByType(inputVideoMime)
        videoDecoder.configure(inputVideoFormat, null, null, 0)

        videoEncoder = MediaCodec.createEncoderByType(inputVideoMime)
        videoEncoder.configure(outputVideoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        val videoMetaData = MediaMetadataRetriever()
        videoMetaData.setDataSource(inputFilePath)
        val degreeString = videoMetaData.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
        val videoDegree = degreeString?.toInt() ?: 0
        muxer = MediaMuxer(outputFilePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.setOrientationHint(videoDegree)
    }

    fun doExtractDecodeEncodeMux() {
        videoExtractor.selectTrack(videoTrackIdx)
        videoDecoder.start()
        videoEncoder.start()

        while (!isMuxEnd) {
            if (!isVideoExtractEnd) extractVideo()
            if (!isVideoDecodeEnd) decodeVideo()
            if (!isVideoEncodeEnd) encodeVideo()
        }

        videoExtractor.release()
        videoDecoder.stop()
        videoDecoder.release()
        videoEncoder.stop()
        videoEncoder.release()
        muxer.stop()
        muxer.release()
    }

    private fun extractVideo() {
        val inputBufferIdx = videoDecoder.dequeueInputBuffer(CODEC_TIMEOUT_IN_US)
        if (inputBufferIdx >= 0) {
            val inputBuffer = videoDecoder.getInputBuffer(inputBufferIdx) as ByteBuffer
            val sampleSize = videoExtractor.readSampleData(inputBuffer, 0)
            if (sampleSize > 0) {
                videoDecoder.queueInputBuffer(inputBufferIdx, 0, sampleSize, videoExtractor.sampleTime, videoExtractor.sampleFlags)
            } else {
                Logger.e("isVideoExtractEnd = true")
                isVideoExtractEnd = true
                videoDecoder.queueInputBuffer(inputBufferIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }

            if (!isVideoExtractEnd) {
                videoExtractor.advance()
            }
        }
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

    private fun encodeVideo() {
        val encoderOutputBufferInfo = MediaCodec.BufferInfo()
        val encoderOutputBufferIdx = videoEncoder.dequeueOutputBuffer(encoderOutputBufferInfo, CODEC_TIMEOUT_IN_US)

        if (encoderOutputBufferIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            Logger.e("output format changed: ${videoEncoder.outputFormat}")
            outputVideoFormat = videoEncoder.outputFormat
            outputVideoFormat?.let {
                outputVideoTrackIdx = muxer.addTrack(it)
                muxer.start()
                return
            }
        }

        if (encoderOutputBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            videoEncoder.releaseOutputBuffer(encoderOutputBufferIdx, false)
            return
        }

        outputVideoFormat?: return

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
    }
}