// Cloudflare Worker: WebSocket 기반 테트리스 매칭 및 중계 서버
let waitingSocket = null;

export default {
  async fetch(request, env, ctx) {
    const upgradeHeader = request.headers.get('Upgrade');
    if (!upgradeHeader || upgradeHeader !== 'websocket') {
      return new Response('Tetris PvP WebSocket Server (Expected Upgrade: websocket)', { status: 426 });
    }

    // WebSocket 페어 생성 (client는 브라우저 연결용, server는 Worker 내부 처리용)
    const webSocketPair = new WebSocketPair();
    const client = webSocketPair[0];
    const server = webSocketPair[1];

    // 서버 측 소켓 활성화
    server.accept();

    if (waitingSocket && waitingSocket.readyState === WebSocket.OPEN) {
      // 대기 중인 플레이어가 있으면 매칭 성사
      const p1 = waitingSocket;
      const p2 = server;
      waitingSocket = null; // 대기열 비우기

      // 양측에 매칭 성공 알림 (P1을 방장/Host로 지정)
      p1.send(JSON.stringify({ type: 'MATCH_FOUND', isHost: true }));
      p2.send(JSON.stringify({ type: 'MATCH_FOUND', isHost: false }));

      // 서로의 메시지를 상대방에게 실시간 중계 (Relay)
      p1.addEventListener('message', event => {
        if (p2.readyState === WebSocket.OPEN) p2.send(event.data);
      });
      p2.addEventListener('message', event => {
        if (p1.readyState === WebSocket.OPEN) p1.send(event.data);
      });
      
      // 연결 종료 처리
      const handleClose = () => {
          try { if (p1.readyState === WebSocket.OPEN) p1.send(JSON.stringify({ type: 'DISCONNECT' })); } catch(e){}
          try { if (p2.readyState === WebSocket.OPEN) p2.send(JSON.stringify({ type: 'DISCONNECT' })); } catch(e){}
          try { p1.close(); } catch(e){}
          try { p2.close(); } catch(e){}
      };
      
      p1.addEventListener('close', handleClose);
      p2.addEventListener('close', handleClose);
      p1.addEventListener('error', handleClose);
      p2.addEventListener('error', handleClose);

    } else {
      // 대기 중인 플레이어가 없으면 현재 플레이어를 대기열에 등록
      waitingSocket = server;
      
      server.addEventListener('close', () => {
        if (waitingSocket === server) waitingSocket = null;
      });
      server.addEventListener('error', () => {
        if (waitingSocket === server) waitingSocket = null;
      });
    }

    return new Response(null, {
      status: 101,
      webSocket: client,
    });
  },
};