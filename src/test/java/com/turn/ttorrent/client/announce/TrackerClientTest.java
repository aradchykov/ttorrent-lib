package com.turn.ttorrent.client.announce;

import com.turn.ttorrent.Utils;
import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.common.protocol.TrackerMessage;
import com.turn.ttorrent.tracker.Tracker;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.mockito.ArgumentMatchers;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@Test
public class TrackerClientTest {

  private Tracker tracker;

  public TrackerClientTest() {
    if (Logger.getRootLogger().getAllAppenders().hasMoreElements())
      return;
    BasicConfigurator.configure(new ConsoleAppender(new PatternLayout("[%d{MMdd HH:mm:ss,SSS}] %6p - %20.20c - %m %n")));
    Logger.getRootLogger().setLevel(Utils.getLogLevel());
    Torrent.setHashingThreadsCount(1);
  }

  @BeforeMethod
  protected void setUp() throws Exception {
    Torrent.setHashingThreadsCount(1);
    startTracker();
  }


  @Test
  public void multiAnnounceTest() throws AnnounceException {
    List<Peer> peers = Collections.singletonList(new Peer(new InetSocketAddress("127.0.0.1", 6881), ByteBuffer.allocate(1)));
    final URI trackerURI = URI.create("http://localhost:6969/announce");
    TrackerClient client = new HTTPTrackerClient(peers, trackerURI);

    final SharedTorrent firstTorrent = getMockedTorrent(new byte[]{1, 2, 3, 4});
    final SharedTorrent secondTorrent = getMockedTorrent(new byte[]{1, 3, 3, 2});
    List<SharedTorrent> torrents = Arrays.asList(firstTorrent, secondTorrent);

    client.multiAnnounce(TrackerMessage.AnnounceRequestMessage.RequestEvent.STARTED, true, torrents, peers);

    peers = Collections.singletonList(new Peer(new InetSocketAddress("127.0.0.1", 6882), ByteBuffer.allocate(1)));
    client.multiAnnounce(TrackerMessage.AnnounceRequestMessage.RequestEvent.STARTED, true, torrents, peers);

    List<Peer> leecher = Collections.singletonList(new Peer(new InetSocketAddress("127.0.0.1", 6885), ByteBuffer.allocate(1)));
    final SharedTorrent firstTorrentLeech = getMockedTorrent(new byte[]{1, 2, 3, 4});
    final SharedTorrent secondTorrentLeech = getMockedTorrent(new byte[]{1, 3, 3, 2});
    when(firstTorrentLeech.getLeft()).thenReturn(10L);
    when(secondTorrentLeech.getLeft()).thenReturn(10L);

    AnnounceResponseListener listener = mock(AnnounceResponseListener.class);

    client.register(listener);
    client.multiAnnounce(TrackerMessage.AnnounceRequestMessage.RequestEvent.STARTED, false,
            Arrays.asList(secondTorrentLeech, firstTorrentLeech), leecher);

    verify(listener, times(2)).handleAnnounceResponse(anyInt(), anyInt(), anyInt(), anyString());
    verify(listener, times(2)).handleDiscoveredPeers(ArgumentMatchers.<Peer>anyList(), anyString());

  }

  private SharedTorrent getMockedTorrent(byte[] hash) {
    final SharedTorrent result = mock(SharedTorrent.class);
    when(result.getLeft()).thenReturn(0L);
    when(result.getDownloaded()).thenReturn(0L);
    when(result.getUploaded()).thenReturn(0L);
    when(result.getInfoHash()).thenReturn(hash);
    when(result.getHexInfoHash()).thenReturn(Torrent.byteArrayToHexString(hash));
    return result;
  }

  private void startTracker() throws IOException {
    this.tracker = new Tracker(6969);
    tracker.setAnnounceInterval(5);
    tracker.setPeerCollectorExpireTimeout(10);
    this.tracker.start(true);
  }

  private void stopTracker() {
    this.tracker.stop();
  }

  @AfterMethod
  protected void tearDown() throws Exception {
    stopTracker();
  }
}
