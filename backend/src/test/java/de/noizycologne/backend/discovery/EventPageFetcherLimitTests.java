package de.noizycologne.backend.discovery;

import org.junit.jupiter.api.Test;
import java.io.*;
import static org.assertj.core.api.Assertions.*;

class EventPageFetcherLimitTests {
    @Test void modernWixPageAboveOldOneMegabyteLimitIsAccepted() throws Exception {
        byte[] body=new byte[1_046_644];
        assertThat(new EventPageFetcher().readBody(new ByteArrayInputStream(body),Long.MAX_VALUE)).hasSize(body.length);
    }
    @Test void exactLimitIsAcceptedAndOneExtraByteIsRejected() throws Exception {
        var fetcher=new EventPageFetcher(65536);
        assertThat(fetcher.readBody(new ByteArrayInputStream(new byte[65536]),Long.MAX_VALUE)).hasSize(65536);
        assertThatThrownBy(() -> fetcher.readBody(new ByteArrayInputStream(new byte[65537]),Long.MAX_VALUE))
                .isInstanceOf(EventPageFetcher.FetchFailure.class).hasMessage("RESPONSE_TOO_LARGE");
    }
    @Test void unknownLengthStreamCannotBypassSizeLimit() {
        var endless=new InputStream() {
            public int read() { return 0; }
            public int read(byte[] buffer) { java.util.Arrays.fill(buffer,(byte)0); return buffer.length; }
        };
        assertThatThrownBy(() -> new EventPageFetcher(65536).readBody(endless,Long.MAX_VALUE))
                .isInstanceOf(EventPageFetcher.FetchFailure.class).hasMessage("RESPONSE_TOO_LARGE");
    }
    @Test void timeoutIsDistinctFromTooLargeResponse() {
        assertThatThrownBy(() -> new EventPageFetcher().readBody(new ByteArrayInputStream(new byte[10]),System.nanoTime()-1))
                .isInstanceOf(EventPageFetcher.FetchFailure.class).hasMessage("FETCH_TIMEOUT");
    }
    @Test void responseBudgetCannotBeDisabledOrRaisedAboveHardCap() {
        for(int limit:new int[]{-1,0,65535,EventPageFetcher.HARD_MAX_BYTES+1,Integer.MAX_VALUE})
            assertThatThrownBy(() -> new EventPageFetcher(limit)).isInstanceOf(IllegalArgumentException.class);
    }
}
