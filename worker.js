// Cloudflare Worker: WebSocket 기반 테트리스 매칭 및 중계 서버

let waitingSocket = null;

export default {
  async fetch(request, env, ctx) {

    // 브라우저에서 접속하면 index.html 등 정적 파일 반환
    const upgradeHeader = request.headers.get("Upgrade");

    if (upgradeHeader !== "websocket") {
      return env.ASSETS.fetch(request);
    }

    // WebSocket 페어 생성
    const webSocketPair = new WebSocketPair();
    const client = webSocketPair[0];
    const server = webSocketPair[1];

    server.accept();

    if (waitingSocket && waitingSocket.readyState === WebSocket.OPEN) {

      // 대기 중인 플레이어와 매칭
      const p1 = waitingSocket;
      const p2 = server;
      waitingSocket = null;

      // 매칭 완료
      p1.send(JSON.stringify({
        type: "MATCH_FOUND",
        isHost: true
      }));

      p2.send(JSON.stringify({
        type: "MATCH_FOUND",
        isHost: false
      }));

      // 메시지 중계
      p1.addEventListener("message", event => {
        if (p2.readyState === WebSocket.OPEN) {
          p2.send(event.data);
        }
      });

      p2.addEventListener("message", event => {
        if (p1.readyState === WebSocket.OPEN) {
          p1.send(event.data);
        }
      });

      // 연결 종료 처리
      const disconnect = () => {

        try {
          if (p1.readyState === WebSocket.OPEN) {
            p1.send(JSON.stringify({
              type: "DISCONNECT"
            }));
          }
        } catch (e) {}

        try {
          if (p2.readyState === WebSocket.OPEN) {
            p2.send(JSON.stringify({
              type: "DISCONNECT"
            }));
          }
        } catch (e) {}

        try { p1.close(); } catch (e) {}
        try { p2.close(); } catch (e) {}
      };

      p1.addEventListener("close", disconnect);
      p2.addEventListener("close", disconnect);

      p1.addEventListener("error", disconnect);
      p2.addEventListener("error", disconnect);

    } else {

      // 대기열 등록
      waitingSocket = server;

      server.addEventListener("close", () => {
        if (waitingSocket === server) {
          waitingSocket = null;
        }
      });

      server.addEventListener("error", () => {
        if (waitingSocket === server) {
          waitingSocket = null;
        }
      });

    }

    return new Response(null, {
      status: 101,
      webSocket: client
    });

  }
};
