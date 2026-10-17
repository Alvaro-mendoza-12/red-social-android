import { WebRtcPeerSession } from '../calls/WebRtcPeerSession';
import { replacePeerTracks } from '../../core/replacePeerTracks';

export class WebRTCManager {
  private localStream: MediaStream | null = null;
  private mediaRevision = 0;
  private peerConnections: Map<string, RTCPeerConnection> = new Map();
  private peerSessions: Map<string, WebRtcPeerSession> = new Map();
  private remoteStreams: Map<string, MediaStream> = new Map();
  private dataChannels: Map<string, RTCDataChannel> = new Map();
  private onTrackCallbacks: ((track: MediaStreamTrack, stream: MediaStream, peerId: string) => void)[] = [];
  private onDataChannelCallbacks: ((data: any, from: string) => void)[] = [];
  private onConnectionStateCallbacks: ((state: RTCPeerConnectionState, peerId: string) => void)[] = [];
  private onIceCandidateCallback: ((peerId: string, candidate: RTCIceCandidateInit) => void) | null = null;
  private onOfferNeededCallback: ((peerId: string, offer: RTCSessionDescriptionInit) => void) | null = null;
  private iceServers: RTCIceServer[] = [
    { urls: 'stun:stun.l.google.com:19302' },
    { urls: 'stun:stun1.l.google.com:19302' }
  ];
  private readonly userId: string;

  constructor(userId: string) {
    this.userId = userId;
  }

  async initializeLocalMedia(audio: boolean = true, video: boolean = true): Promise<MediaStream> {
    const revision = ++this.mediaRevision;
    try {
      const constraints = {
        audio,
        video: video ? {
          width: { ideal: 1280 },
          height: { ideal: 720 }
        } : false
      };

      const stream = await navigator.mediaDevices.getUserMedia(constraints);
      if (revision !== this.mediaRevision) { stream.getTracks().forEach(track => track.stop()); throw new Error('Media session ended'); }
      this.localStream?.getTracks().forEach(track => track.stop());
      this.localStream = stream;
      return this.localStream;
    } catch (error) {
      console.error('Error accessing media devices:', error);
      throw new Error('No se pudo acceder a los dispositivos de audio/vídeo');
    }
  }

  setLocalStream(stream: MediaStream, stopPrevious: boolean = true): void {
    this.mediaRevision++;
    if (stopPrevious && this.localStream && this.localStream !== stream) {
      this.localStream.getTracks().forEach((track) => track.stop());
    }
    this.localStream = stream;
  }

  async replaceOutgoingTrack(kind: 'audio' | 'video', track: MediaStreamTrack): Promise<void> {
    if (!this.localStream) this.localStream = new MediaStream();
    const stream = this.localStream, revision = this.mediaRevision;
    await replacePeerTracks(this.peerConnections.values(), kind, track, stream);
    if (revision !== this.mediaRevision || stream !== this.localStream) { track.stop(); throw new Error('Media session ended'); }
    this.localStream.getTracks()
      .filter((current) => current.kind === kind && current.id !== track.id)
      .forEach((current) => {
        this.localStream?.removeTrack(current);
        current.stop();
      });
    if (!this.localStream.getTracks().some((current) => current.id === track.id)) {
      this.localStream.addTrack(track);
    }

  }

  async removeOutgoingTrack(kind: 'audio' | 'video'): Promise<void> {
    if (!this.localStream) return;
    await replacePeerTracks(this.peerConnections.values(), kind, null, this.localStream);
    this.localStream?.getTracks().filter(track => track.kind === kind).forEach(track => { track.stop(); this.localStream?.removeTrack(track); });
  }

  createPeerConnection(peerId: string, isInitiator: boolean): RTCPeerConnection {
    const existing = this.peerConnections.get(peerId);
    if (existing) return existing;
    const configuration = {
      iceServers: this.iceServers
    };

    const peerConnection = new RTCPeerConnection(configuration);
    
    peerConnection.onicecandidate = (event) => {
      if (event.candidate) {
        this.sendIceCandidate(peerId, event.candidate.toJSON());
      }
    };

    peerConnection.ontrack = (event) => {
      {
        // Android Unified Plan can announce streamless tracks. Keep one stable
        // stream per peer, including video added after the audio connection.
        const stream = this.remoteStreams.get(peerId) || new MediaStream();
        this.remoteStreams.set(peerId, stream);
        if (!stream.getTracks().some(track => track.id === event.track.id)) stream.addTrack(event.track);
        this.onTrackCallbacks.forEach(callback => {
          callback(event.track, stream, peerId);
        });
      }
    };

    peerConnection.onconnectionstatechange = () => {
      this.peerSessions.get(peerId)?.handleConnectionState(peerConnection.connectionState);
      this.onConnectionStateCallbacks.forEach(callback => {
        callback(peerConnection.connectionState, peerId);
      });
    };

    if (this.localStream) {
      this.localStream.getTracks().forEach(track => {
        peerConnection.addTrack(track, this.localStream!);
      });
    }
    for (const kind of ['audio', 'video'] as const) {
      if (!peerConnection.getTransceivers().some(value => value.receiver.track.kind === kind)) {
        peerConnection.addTransceiver(kind, { direction: 'recvonly' });
      }
    }

    this.peerConnections.set(peerId, peerConnection);
    const session = new WebRtcPeerSession(peerConnection, {
      polite: !isInitiator,
      initialOfferer: isInitiator,
      sendOffer: (offer) => this.onOfferNeededCallback?.(peerId, offer),
    });
    this.peerSessions.set(peerId, session);
    session.bindNegotiation();
    return peerConnection;
  }

  async createOffer(peerId: string): Promise<RTCSessionDescriptionInit> {
    const session = this.peerSessions.get(peerId);
    if (!session) {
      throw new Error('Peer connection not found');
    }

    const offer = await session.negotiate();
    if (!offer) throw new Error('Peer connection is not ready to negotiate');
    return offer;
  }

  async createAnswer(peerId: string, offer: RTCSessionDescriptionInit): Promise<RTCSessionDescriptionInit | null> {
    const session = this.peerSessions.get(peerId);
    if (!session) {
      throw new Error('Peer connection not found');
    }

    return session.receiveOffer(offer);
  }

  async handleAnswer(peerId: string, answer: RTCSessionDescriptionInit): Promise<void> {
    const session = this.peerSessions.get(peerId);
    if (!session) {
      throw new Error('Peer connection not found');
    }

    await session.receiveAnswer(answer);
  }

  async handleIceCandidate(peerId: string, candidate: RTCIceCandidateInit): Promise<void> {
    const session = this.peerSessions.get(peerId);
    if (!session) {
      throw new Error('Peer connection not found');
    }

    await session.addIceCandidate(candidate);
  }

  createDataChannel(peerId: string, label: string = 'data'): RTCDataChannel {
    const peerConnection = this.peerConnections.get(peerId);
    if (!peerConnection) {
      throw new Error('Peer connection not found');
    }

    const dataChannel = peerConnection.createDataChannel(label);
    this.setupDataChannel(dataChannel, peerId);
    this.dataChannels.set(peerId, dataChannel);
    return dataChannel;
  }

  setupDataChannel(dataChannel: RTCDataChannel, peerId: string): void {
    dataChannel.onopen = () => {
      console.log(`Data channel opened with ${peerId}`);
    };

    dataChannel.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        this.onDataChannelCallbacks.forEach(callback => {
          callback(data, peerId);
        });
      } catch (error) {
        console.error('Error parsing data channel message:', error);
      }
    };

    dataChannel.onclose = () => {
      console.log(`Data channel closed with ${peerId}`);
      this.dataChannels.delete(peerId);
    };
  }

  sendData(peerId: string, data: any): void {
    const dataChannel = this.dataChannels.get(peerId);
    if (dataChannel && dataChannel.readyState === 'open') {
      dataChannel.send(JSON.stringify(data));
    }
  }

  broadcastData(data: any): void {
    this.dataChannels.forEach((channel, peerId) => {
      if (channel.readyState === 'open') {
        channel.send(JSON.stringify(data));
      }
    });
  }

  onTrack(callback: (track: MediaStreamTrack, stream: MediaStream, peerId: string) => void): void {
    this.onTrackCallbacks.push(callback);
  }

  onDataChannel(callback: (data: any, from: string) => void): void {
    this.onDataChannelCallbacks.push(callback);
  }

  onConnectionState(callback: (state: RTCPeerConnectionState, peerId: string) => void): void {
    this.onConnectionStateCallbacks.push(callback);
  }

  onIceCandidate(callback: (peerId: string, candidate: RTCIceCandidateInit) => void): void {
    this.onIceCandidateCallback = callback;
  }

  onOfferNeeded(callback: (peerId: string, offer: RTCSessionDescriptionInit) => void): void {
    this.onOfferNeededCallback = callback;
  }

  setIceServers(iceServers: RTCIceServer[]): void {
    if (iceServers.length > 0) {
      this.iceServers = iceServers;
    }
  }

  hasPeerConnection(peerId: string): boolean {
    return this.peerConnections.has(peerId);
  }

  disconnectPeer(peerId: string): void {
    this.remoteStreams.delete(peerId);
    const peerConnection = this.peerConnections.get(peerId);
    const dataChannel = this.dataChannels.get(peerId);

    if (dataChannel) {
      dataChannel.close();
      this.dataChannels.delete(peerId);
    }

    if (peerConnection) {
      this.peerSessions.get(peerId)?.dispose();
      this.peerSessions.delete(peerId);
      peerConnection.close();
      this.peerConnections.delete(peerId);
    }
  }

  disconnectAll(): void {
    this.peerConnections.forEach((_, peerId) => {
      this.disconnectPeer(peerId);
    });
  }

  stopLocalStream(): void {
    this.mediaRevision++;
    if (this.localStream) {
      this.localStream.getTracks().forEach(track => {
        track.stop();
      });
      this.localStream = null;
    }
  }

  getLocalStream(): MediaStream | null {
    return this.localStream;
  }

  private async sendIceCandidate(peerId: string, candidate: RTCIceCandidateInit): Promise<void> {
    this.onIceCandidateCallback?.(peerId, candidate);
  }

  getConnectionStats(): Map<string, RTCPeerConnectionState> {
    const stats = new Map<string, RTCPeerConnectionState>();
    this.peerConnections.forEach((connection, peerId) => {
      stats.set(peerId, connection.connectionState);
    });
    return stats;
  }

  isPeerConnected(peerId: string): boolean {
    const connection = this.peerConnections.get(peerId);
    return connection ? connection.connectionState === 'connected' : false;
  }

  getConnectedPeers(): string[] {
    const connectedPeers: string[] = [];
    this.peerConnections.forEach((connection, peerId) => {
      if (connection.connectionState === 'connected') {
        connectedPeers.push(peerId);
      }
    });
    return connectedPeers;
  }
}

export default WebRTCManager;
