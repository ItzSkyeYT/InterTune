package com.zionhuang.innertube

import com.zionhuang.innertube.models.response.PlayerResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.io.IOException

/**
 * The n step's failures, told apart: a network that failed while YouTube's player script was
 * fetched is said as the network's, so the app's walk takes it for no answer and strikes no
 * client. NewPipeExtractor wraps it in a ParsingException, which read as a script it could not
 * read. No request leaves: the downloader here fails every one.
 */
class NewPipeNetworkFailureTest {
    private fun format(url: String) = PlayerResponse.StreamingData.Format(
        itag = 251, url = url,
        mimeType = "audio/webm; codecs=\"opus\"", bitrate = 140_000, width = null, height = null,
        contentLength = 3_000_000, quality = "tiny", fps = null, qualityLabel = null, averageBitrate = null,
        audioQuality = null, approxDurationMs = null, audioSampleRate = 48_000, audioChannels = 2,
        loudnessDb = null, lastModified = null, signatureCipher = null,
    )

    private fun <T> offline(block: () -> T): T {
        // Starts NewPipeUtils (which sets its own downloader) and empties NewPipe's caches.
        NewPipeUtils.clearAfterFailure(everyMs = 0)
        val before = NewPipe.getDownloader()
        NewPipe.init(object : Downloader() {
            override fun execute(request: Request): Response = throw IOException("timeout")
        })
        try {
            return block()
        } finally {
            NewPipe.init(before)
        }
    }

    @Test
    fun `an n step that failed for want of a network says so, and is not taken for a script it cannot read`() {
        val failure = offline {
            NewPipeUtils.getStreamUrl(format("https://rr1---sn-test.googlevideo.com/videoplayback?itag=251&n=Zx9qLmNoPqRs"), "TESTVIDEO01").exceptionOrNull()
        }
        assertTrue("the network's failure reads as the network's: $failure", failure is IOException)
    }

    @Test
    fun `an address with no n needs neither the script nor the network`() {
        val url = "https://rr1---sn-test.googlevideo.com/videoplayback?itag=251"
        assertEquals(url, offline { NewPipeUtils.getStreamUrl(format(url), "TESTVIDEO01").getOrThrow() })
    }
}
