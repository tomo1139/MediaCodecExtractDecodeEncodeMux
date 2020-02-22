package develop.tomo1139.mediacodecextractdecodeencodemux.media

import android.media.*
import develop.tomo1139.mediacodecextractdecodeencodemux.util.Logger
import java.lang.RuntimeException
import java.nio.ByteBuffer

class ExtractDecodeEncodeMuxer(inputFilePath: String, private val outputFilePath: String) {

    private val videoExtractor = MediaExtractor()
    private val videoTrackIdx: Int
    private val inputVideoFormat: MediaFormat
    private val outputVideoTrackIdx: Int

    private val workingBuffer = ByteBuffer.allocate(256 * 1024)

    private val muxer: MediaMuxer

    private var isVideoExtractEnd = false
    private var isMuxEnd = false

    init {
        videoExtractor.setDataSource(inputFilePath)
        videoTrackIdx = getVideoTrackIdx(videoExtractor)
        if (videoTrackIdx == -1) {
            Logger.e("video not found")
            throw RuntimeException("video not found")
        }
        inputVideoFormat = videoExtractor.getTrackFormat(videoTrackIdx)
        Logger.e("inputVideoFormat: $inputVideoFormat")

        val videoMetaData = MediaMetadataRetriever()
        videoMetaData.setDataSource(inputFilePath)
        val degreeString = videoMetaData.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
        val videoDegree = degreeString?.toInt() ?: 0
        muxer = MediaMuxer(outputFilePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.setOrientationHint(videoDegree)

        outputVideoTrackIdx = muxer.addTrack(inputVideoFormat)
    }

    fun doExtractDecodeEncodeMux() {
        videoExtractor.selectTrack(videoTrackIdx)

        muxer.start()

        while (!isMuxEnd) {
            while (!isVideoExtractEnd) { extract() }
        }

        Logger.e("outputFilePath: $outputFilePath")

        muxer.stop()
        muxer.release()
        videoExtractor.release()
    }

    private fun extract() {
        val sampleSize = videoExtractor.readSampleData(workingBuffer, 0)
        if (sampleSize < 0) {
            Logger.e("isVideoExtractEnd = true")
            isVideoExtractEnd = true
            isMuxEnd = true
        } else {
            val bufferInfo = MediaCodec.BufferInfo()
            bufferInfo.size = sampleSize
            bufferInfo.presentationTimeUs = videoExtractor.sampleTime
            bufferInfo.flags = videoExtractor.sampleFlags
            muxer.writeSampleData(outputVideoTrackIdx, workingBuffer, bufferInfo)
        }

        if (!isVideoExtractEnd) {
            videoExtractor.advance()
        }
        Logger.e("sampleTime: ${videoExtractor.sampleTime}")
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