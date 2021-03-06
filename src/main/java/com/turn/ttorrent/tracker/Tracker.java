/**
 * Copyright (C) 2011-2012 Turn, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.turn.ttorrent.tracker;

import com.turn.ttorrent.common.Torrent;

import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;


import org.simpleframework.http.core.ContainerServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.simpleframework.transport.connect.Connection;
import org.simpleframework.transport.connect.SocketConnection;

/**
 * BitTorrent tracker.
 *
 * <p>
 * The tracker usually listens on port 6969 (the standard BitTorrent tracker
 * port). Torrents must be registered directly to this tracker with the
 * {@link #announce(TrackedTorrent torrent)}</code> method.
 * </p>
 *
 * @author mpetazzoni
 */
public class Tracker {

	private static final Logger logger = LoggerFactory.getLogger(Tracker.class);

	/** Request path handled by the tracker announce request handler. */
	public static final String ANNOUNCE_URL = "/announce";

	/** Default tracker listening port (BitTorrent's default is 6969). */
	public static final int DEFAULT_TRACKER_PORT = 6969;

	/** Default server name and version announced by the tracker. */
	public static final String DEFAULT_VERSION_STRING = "BitTorrent Tracker (ttorrent)";

	private Connection connection;

	/** The in-memory repository of torrents tracked. */
	private final ConcurrentMap<String, TrackedTorrent> myTorrents;

  private PeerCollectorThread myPeerCollectorThread;
	private boolean stop;
  private String myAnnounceUrl;
  private final int myPort;
  private SocketAddress myBoundAddress = null;

  private final TrackerServiceContainer myTrackerServiceContainer;

	/**
	 * Create a new BitTorrent tracker listening at the given address.
	 *
	 * @throws IOException Throws an <em>IOException</em> if the tracker
	 * cannot be initialized.
	 */
	public Tracker(int port) throws IOException {
      this(port,
          getDefaultAnnounceUrl(new InetSocketAddress(InetAddress.getLocalHost(), port)).toString()
      );
    }
	public Tracker(int port, String announceURL) throws IOException {
    this (port, announceURL, new TrackerRequestProcessor(), new ConcurrentHashMap<String, TrackedTorrent>());
  }

	public Tracker(int port, String announceURL, TrackerRequestProcessor requestProcessor, final ConcurrentMap<String, TrackedTorrent> torrents) throws IOException {
    this.myPort = port;
    this.myAnnounceUrl = announceURL;
    myTorrents = torrents;
    this.myTrackerServiceContainer = new TrackerServiceContainer(requestProcessor, myTorrents);
    myPeerCollectorThread = new PeerCollectorThread(myTorrents);
  }

	/**
	 * Returns the full announce URL served by this tracker.
	 *
	 * <p>
	 * This has the form http://host:port/announce.
	 * </p>
	 */
	private static URL getDefaultAnnounceUrl(InetSocketAddress address) {
		try {
			return new URL("http",
				address.getAddress().getCanonicalHostName(),
				address.getPort(),
				ANNOUNCE_URL);
		} catch (MalformedURLException mue) {
			logger.error("Could not build tracker URL: {}!", mue, mue);
		}

		return null;
	}

  public String getAnnounceUrl() {
    return myAnnounceUrl;
  }

  public URI getAnnounceURI() {
    try {
      URL announceURL = new URL(getAnnounceUrl());
      if (announceURL != null) {
        return announceURL.toURI();
      }
    } catch (URISyntaxException e) {
      logger.error("Cannot convert announce URL to URI", e);
    } catch (MalformedURLException e) {
      logger.error("Cannot create URL from announceURL", e);
    }
    return null;
  }

  /**
	 * Start the tracker thread.
	 */
	public void start(final boolean startPeerCleaningThread) throws IOException {
    logger.info("Starting BitTorrent tracker on {}...",
            getAnnounceUrl());
    connection = new SocketConnection(new ContainerServer(myTrackerServiceContainer));

    List<SocketAddress> tries = new ArrayList<SocketAddress>() {{
      try {add(new InetSocketAddress(InetAddress.getByAddress(new byte[4]), myPort));} catch (Exception ex) {}
      try {add(new InetSocketAddress(InetAddress.getLocalHost(), myPort));} catch (Exception ex) {}
      try {add(new InetSocketAddress(InetAddress.getByName(new URL(getAnnounceUrl()).getHost()), myPort));} catch (Exception ex) {}
    }};

    boolean started = false;
    for (SocketAddress address : tries) {
      try {
        if ((myBoundAddress = connection.connect(address)) != null) {
          logger.info("Started torrent tracker on {}", address);
          started = true;
          break;
        }
      } catch (IOException ioe) {
        logger.info("Can't start the tracker using address{} : ", address.toString(), ioe.getMessage());
      }
    }
    if (!started){
      logger.error("Cannot start tracker on port {}. Stopping now...", myPort);
      stop();
      return;
    }
		if (startPeerCleaningThread) {
      if (this.myPeerCollectorThread == null || !this.myPeerCollectorThread.isAlive() || myPeerCollectorThread.getState() != Thread.State.NEW){
        myPeerCollectorThread = new PeerCollectorThread(myTorrents);
      }

      this.myPeerCollectorThread.setName("peer-peerCollectorThread:" + myPort);
			this.myPeerCollectorThread.start();
		}
	}

	/**
	 * Stop the tracker.
	 *
	 * <p>
	 * This effectively closes the listening HTTP connection to terminate
	 * the service, and interrupts the peer myPeerCollectorThread thread as well.
	 * </p>
	 */
	public void stop() {
		this.stop = true;

		try {
			this.connection.close();
			logger.info("BitTorrent tracker closed.");
		} catch (IOException ioe) {
			logger.error("Could not stop the tracker: {}!", ioe.getMessage());
		}

		if (this.myPeerCollectorThread != null && this.myPeerCollectorThread.isAlive()) {
			this.myPeerCollectorThread.interrupt();
            try {
                this.myPeerCollectorThread.join();
            } catch (InterruptedException e) {
                //
            }
            logger.info("Peer collection terminated.");
        }
    }

    /**
	 * Announce a new torrent on this tracker.
	 *
	 * <p>
	 * The fact that torrents must be announced here first makes this tracker a
	 * closed BitTorrent tracker: it will only accept clients for torrents it
	 * knows about, and this list of torrents is managed by the program
	 * instrumenting this Tracker class.
	 * </p>
	 *
	 * @param torrent The Torrent object to start tracking.
	 * @return The torrent object for this torrent on this tracker. This may be
	 * different from the supplied Torrent object if the tracker already
	 * contained a torrent with the same hash.
	 */
	public synchronized TrackedTorrent announce(TrackedTorrent torrent) {
		TrackedTorrent existing = this.myTorrents.get(torrent.getHexInfoHash());

		if (existing != null) {
			logger.warn("Tracker already announced torrent with hash {}.", existing.getHexInfoHash());
			return existing;
		}

		this.myTorrents.put(torrent.getHexInfoHash(), torrent);
		logger.info("Registered new torrent with hash {}.", torrent.getHexInfoHash());
		return torrent;
    }

    /**
   * Stop announcing the torrent with given hash.
   * @param info_hash torrent hash
	 */
	public synchronized boolean remove(byte[] info_hash) {
    return info_hash != null &&
           this.myTorrents.remove(Torrent.byteArrayToHexString(info_hash)) != null;
  }

  /**
     * Set to true to allow this tracker to track external torrents (i.e. those that were not explicitly announced here).
     * @param acceptForeignTorrents true to accept foreign torrents (false otherwise)
     */
    public void setAcceptForeignTorrents(boolean acceptForeignTorrents) {
        this.myTrackerServiceContainer.setAcceptForeignTorrents(acceptForeignTorrents);
    }

	/**
   * @return all tracked torrents.
   */
  public Collection<TrackedTorrent> getTrackedTorrents() {
    return Collections.unmodifiableCollection(this.myTorrents.values());
  }

  public TrackedTorrent getTrackedTorrent(String hash){
    return myTorrents.get(hash);
  }

  public void setAnnounceInterval(int announceInterval){
    myTrackerServiceContainer.setAnnounceInterval(announceInterval);
  }

  public void setPeerCollectorExpireTimeout(int expireTimeout){
    myPeerCollectorThread.setTorrentExpireTimeoutSec(expireTimeout);
  }
}
