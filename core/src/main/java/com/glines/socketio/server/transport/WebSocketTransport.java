/**
 * The MIT License
 * Copyright (c) 2010 Tad Glines
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.glines.socketio.server.transport;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpConnection;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.websocket.WebSocket;
import org.eclipse.jetty.websocket.WebSocketFactory;

import com.glines.socketio.common.ConnectionState;
import com.glines.socketio.common.DisconnectReason;
import com.glines.socketio.common.SocketIOException;
import com.glines.socketio.server.SocketIOClosedException;
import com.glines.socketio.server.SocketIOInbound;
import com.glines.socketio.server.SocketIOFrame;
import com.glines.socketio.server.SocketIOSession;
import com.glines.socketio.server.Transport;

public class WebSocketTransport extends AbstractTransport implements WebSocketFactory.Acceptor {
	public static final String TRANSPORT_NAME = "websocket";
	public static final long CONNECTION_TIMEOUT = 10*1000;
	private final WebSocketFactory wsFactory;
	private final long maxIdleTime;

  @Override
  public WebSocket doWebSocketConnect(HttpServletRequest httpServletRequest, String s) {
    throw new RuntimeException("This method isn't used by socket io");
  }

  @Override
  public String checkOrigin(HttpServletRequest httpServletRequest, String host, String origin) {
    if (origin == null) {
      origin = host;
    }
    return origin;
  }

  private class SessionWrapper implements WebSocket.OnTextMessage, SocketIOSession.SessionTransportHandler {
		private final SocketIOSession session;
		private Connection outbound = null;
		private boolean initiated = false;

		SessionWrapper(SocketIOSession session) {
			this.session = session;
      session.setHeartbeat(maxIdleTime/2);
      session.setTimeout(CONNECTION_TIMEOUT);
		}
		
		/*
		 * (non-Javadoc)
		 * @see org.eclipse.jetty.websocket.WebSocket#onConnect(org.eclipse.jetty.websocket.WebSocket.Outbound)
		 */
		@Override
		public void onOpen(final Connection outbound) {
			this.outbound = outbound;
		}

        /*
           * (non-Javadoc)
           * @see org.eclipse.jetty.websocket.WebSocket#onDisconnect()
           */
		@Override
		public void onClose(int code, String reason) {
			session.onShutdown();
		}

		/*
		 * (non-Javadoc)
		 * @see com.glines.socketio.SocketIOInbound.SocketIOOutbound#disconnect()
		 */
		@Override
		public void disconnect() {
			session.onDisconnect(DisconnectReason.DISCONNECT);
			outbound.disconnect();
		}

		@Override
		public void close() {
			session.startClose();
		}

		@Override
		public ConnectionState getConnectionState() {
			return session.getConnectionState();
		}

		@Override
		public void sendMessage(SocketIOFrame frame) throws SocketIOException {
			if (outbound.isOpen()) {
				Log.debug("Session["+session.getSessionId()+"]: sendMessage: [" + frame.getFrameType() + "]: " + frame.getData());
				try {
					outbound.sendMessage(frame.encode());
				} catch (IOException e) {
					outbound.disconnect();
					throw new SocketIOException(e);
				}
			} else {
				throw new SocketIOClosedException();
			}
		}
		
		
		/*
		 * (non-Javadoc)
		 * @see com.glines.socketio.SocketIOInbound.SocketIOOutbound#sendMessage(java.lang.String)
		 */
		@Override
		public void sendMessage(String message) throws SocketIOException {
			sendMessage(SocketIOFrame.TEXT_MESSAGE_TYPE, message);
		}

		@Override
		public void sendMessage(int messageType, String message)
				throws SocketIOException {
			if (outbound.isOpen() && session.getConnectionState() == ConnectionState.CONNECTED) {
				sendMessage(new SocketIOFrame(SocketIOFrame.FrameType.DATA, messageType, message));
			} else {
				throw new SocketIOClosedException();
			}
		}

		/*
		 * (non-Javadoc)
		 * @see com.glines.socketio.SocketIOSession.SessionTransportHandler#handle(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse, com.glines.socketio.SocketIOSession)
		 */
		@Override
		public void handle(HttpServletRequest request,
				HttpServletResponse response, SocketIOSession session) throws IOException {
    		response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Unexpected request on upgraded WebSocket connection");
    		return;
		}

		@Override
		public void disconnectWhenEmpty() {
		}

		@Override
		public void abort() {
			outbound.disconnect();
			outbound = null;
			session.onShutdown();
		}

    @Override
    public void onMessage(String message) {
      session.startHeartbeatTimer();
			if (!initiated) {
				if ("OPEN".equals(message)) {
					try {
						outbound.sendMessage(SocketIOFrame.encode(SocketIOFrame.FrameType.SESSION_ID, 0, session.getSessionId()));
						outbound.sendMessage(SocketIOFrame.encode(SocketIOFrame.FrameType.HEARTBEAT_INTERVAL, 0, "" + session.getHeartbeat()));
						session.onConnect(this);
						initiated = true;
					} catch (IOException e) {
						outbound.disconnect();
						session.onShutdown();
					}
				} else {
					outbound.disconnect();
					session.onShutdown();
				}
			} else {
				List<SocketIOFrame> messages = SocketIOFrame.parse(message);

				for (SocketIOFrame msg: messages) {
					session.onMessage(msg);
				}
			}
    }
  }

	public WebSocketTransport(int bufferSize, int maxIdleTime) {
		wsFactory = new WebSocketFactory(this, bufferSize);
		wsFactory.setMaxIdleTime(maxIdleTime);
		this.maxIdleTime = maxIdleTime;
	}
	
	@Override
	public String getName() {
		return TRANSPORT_NAME;
	}

  protected String[] parseProtocols(String protocol)
  {
      if (protocol == null)
          return new String[]{null};
      protocol = protocol.trim();
      if (protocol == null || protocol.length() == 0)
          return new String[]{null};
      String[] passed = protocol.split("\\s*,\\s*");
      String[] protocols = new String[passed.length + 1];
      System.arraycopy(passed, 0, protocols, 0, passed.length);
      return protocols;
  }


	@Override
	public void handle(HttpServletRequest request,
			HttpServletResponse response,
			Transport.InboundFactory inboundFactory,
			SocketIOSession.Factory sessionFactory)
			throws IOException {

		String sessionId = extractSessionId(request);

		if ("GET".equals(request.getMethod()) && sessionId == null && "websocket".equalsIgnoreCase(request.getHeader("Upgrade"))) {
			boolean hixie = request.getHeader("Sec-WebSocket-Protocol") != null;

      String protocol=request.getHeader(hixie ? "Sec-WebSocket-Protocol" : "WebSocket-Protocol");
      if (protocol == null)
          protocol=request.getHeader("Sec-WebSocket-Protocol");

      String host = request.getHeader("Host");
      String origin = checkOrigin(request, host, request.getHeader("Origin"));

      SocketIOInbound inbound = inboundFactory.getInbound(request);
      if (inbound == null) {
        response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
      } else {
        SessionWrapper wrapper = null;
        SocketIOSession session = sessionFactory.createSession(inbound);
        for (String p : parseProtocols(protocol))
        {
            wrapper = new SessionWrapper(session);
            if (wrapper != null)
            {
                protocol = p;
                break;
            }
        }

        wsFactory.upgrade(request,response,wrapper,origin, protocol);
      }
		} else {
    		response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid " + TRANSPORT_NAME + " transport request");
		}
	}
}
