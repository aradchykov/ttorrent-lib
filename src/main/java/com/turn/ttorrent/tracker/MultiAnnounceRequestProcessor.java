package com.turn.ttorrent.tracker;

import com.turn.ttorrent.bcodec.BDecoder;
import com.turn.ttorrent.bcodec.BEValue;
import com.turn.ttorrent.bcodec.BEncoder;
import com.turn.ttorrent.common.protocol.TrackerMessage;
import com.turn.ttorrent.common.protocol.http.HTTPTrackerErrorMessage;
import org.simpleframework.http.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class MultiAnnounceRequestProcessor {

  private final TrackerRequestProcessor myTrackerRequestProcessor;

  private static final Logger logger =
          LoggerFactory.getLogger(MultiAnnounceRequestProcessor.class);

  public MultiAnnounceRequestProcessor(TrackerRequestProcessor trackerRequestProcessor) {
    this.myTrackerRequestProcessor = trackerRequestProcessor;
  }

  public void process(final String body, final String url, final String hostAddress, final TrackerRequestProcessor.RequestHandler requestHandler) throws IOException {

    final List<BEValue> responseMessages = new ArrayList<BEValue>();
    final AtomicBoolean isAnySuccess = new AtomicBoolean(false);
    for (String s : body.split("\n")) {
      myTrackerRequestProcessor.process(s, hostAddress, new TrackerRequestProcessor.RequestHandler() {
        @Override
        public void serveResponse(int code, String description, ByteBuffer responseData) {
          isAnySuccess.set(isAnySuccess.get() || (code == Status.OK.getCode()));
          try {
            responseMessages.add(BDecoder.bdecode(responseData));
          } catch (IOException e) {
            logger.warn("cannot decode message from byte buffer");
          }
        }

        @Override
        public ConcurrentMap<String, TrackedTorrent> getTorrentsMap() {
          return requestHandler.getTorrentsMap();
        }
      });
    }
    if (responseMessages.isEmpty()) {
      ByteBuffer res;
      Status status;
      try {
        res = HTTPTrackerErrorMessage.craft("").getData();
        status = Status.BAD_REQUEST;
      } catch (TrackerMessage.MessageValidationException e) {
        logger.warn("Could not craft tracker error message!", e);
        status = Status.INTERNAL_SERVER_ERROR;
        res = ByteBuffer.allocate(0);

      }
      requestHandler.serveResponse(status.getCode(), "", res);
      return;
    }
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    BEncoder.bencode(responseMessages, out);
    requestHandler.serveResponse(isAnySuccess.get() ? Status.OK.getCode() : Status.BAD_REQUEST.getCode(), "", ByteBuffer.wrap(out.toByteArray()));
  }
}
