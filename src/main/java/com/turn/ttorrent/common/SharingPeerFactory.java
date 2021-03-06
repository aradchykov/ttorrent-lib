package com.turn.ttorrent.common;

import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.client.peer.SharingPeer;

import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;

public interface SharingPeerFactory {

  SharingPeer createSharingPeer(String host, int port, ByteBuffer peerId, SharedTorrent torrent, ByteChannel channel);

}
