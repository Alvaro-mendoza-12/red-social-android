export class WebRTCManager {
  private localStream: MediaStream | null = null;
  private peerConnections: Map<string, RTCPeerConnection> = new Map();
  private dataChannels: Map<string, RTCDataChannel> = new Map();
  private onTrackCallbacks: ((track: MediaStreamTrack, stream: MediaStream) => void)[] = [];
  private onDataChannelCallbacks: ((data: any, from: string) => void)[] = [];
  private onConnectionStateCallbacks: ((state: RTCPeerConnectionState, peerId: string) => void)[] = [];

  constructor(private userId: string) {}

  async initializeLocalMedia(audio: boolean = true, video: boolean = true): Promise<MediaStream> {
    try {
      const constraints = {
        audio,
        video: video ? {
          width: { ideal: 1280 },
          height: { ideal: 720 }
        } : false
      };

      this.localStream = await navigator.mediaDevices.getUserMedia(constraints);
      return this.localStream;
    } catch (error) {
      console.error('Error accessing media devices:', error);
      throw new Error('No se pudo acceder a los dispositivos de audio/vídeo');
    }
  }

  createPeerConnection(peerId: string, isInitiator: boolean): RTCPeerConnection {
    const configuration = {
      iceServers: [
        { urls: 'stun:stun.l.google.com:19302' },
        { urls: 'stun:stun1.l.google.com:19302' }
      ]
    };

    const peerConnection = new RTCPeerConnection(configuration);
    
    peerConnection.onicecandidate = (event) => {
      if (event.candidate) {
        this.sendIceCandidate(peerId, event.candidate);
      }
    };

    peerConnection.ontrack = (event) => {
      if (event.streams && event.streams[0]) {
        this.onTrackCallbacks.forEach(callback => {
          callback(event.track, event.streams[0]);
        });
      }
    };

    peerConnection.onconnectionstatechange = () => {
      this.onConnectionStateCallbacks.forEach(callback => {
        callback(peerConnection.connectionState, peerId);
      });
    };

    if (this.localStream) {
      this.localStream.getTracks().forEach(track => {
        peerConnection.addTrack(track, this.localStream!);
      });
    }

    this.peerConnections.set(peerId, peerConnection);
    return peerConnection;
  }

  async createOffer(peerId: string): Promise<RTCSessionDescriptionInit> {
    const peerConnection = this.peerConnections.get(peerId);
    if (!peerConnection) {
      throw new Error('Peer connection not found');
    }

    const offer = await peerConnection.createOffer();
    await peerConnection.setLocalDescription(offer);
    return offer;
  }

  async createAnswer(peerId: string, offer: RTCSessionDescriptionInit): Promise<RTCSessionDescriptionInit> {
    const peerConnection = this.peerConnections.get(peerId);
    if (!peerConnection) {
      throw new Error('Peer connection not found');
    }

    await peerConnection.setRemoteDescription(offer);
    const answer = await peerConnection.createAnswer();
    await peerConnection.setLocalDescription(answer);
    return answer;
  }

  async handleAnswer(peerId: string, answer: RTCSessionDescriptionInit): Promise<void> {
    const peerConnection = this.peerConnections.get(peerId);
    if (!peerConnection) {
      throw new Error('Peer connection not found');
    }

    await peerConnection.setRemoteDescription(answer);
  }

  async handleIceCandidate(peerId: string, candidate: RTCIceCandidateInit): Promise<void> {
    const peerConnection = this.peerConnections.get(peerId);
    if (!peerConnection) {
      throw new Error('Peer connection not found');
    }

    await peerConnection.addIceCandidate(candidate);
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

  onTrack(callback: (track: MediaStreamTrack, stream: MediaStream) => void): void {
    this.onTrackCallbacks.push(callback);
  }

  onDataChannel(callback: (data: any, from: string) => void): void {
    this.onDataChannelCallbacks.push(callback);
  }

  onConnectionState(callback: (state: RTCPeerConnectionState, peerId: string) => void): void {
    this.onConnectionStateCallbacks.push(callback);
  }

  disconnectPeer(peerId: string): void {
    const peerConnection = this.peerConnections.get(peerId);
    const dataChannel = this.dataChannels.get(peerId);

    if (dataChannel) {
      dataChannel.close();
      this.dataChannels.delete(peerId);
    }

    if (peerConnection) {
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
    // This would be implemented with your signaling server
    console.log('Sending ICE candidate to', peerId, candidate);
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
