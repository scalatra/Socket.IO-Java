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

import java.io.BufferedReader;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationListener;
import org.eclipse.jetty.continuation.ContinuationSupport;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;

import com.glines.socketio.common.ConnectionState;
import com.glines.socketio.common.DisconnectReason;
import com.glines.socketio.common.SocketIOException;
import com.glines.socketio.server.SocketIOClosedException;
import com.glines.socketio.server.SocketIOInbound;
import com.glines.socketio.server.SocketIOFrame;
import com.glines.socketio.server.SocketIOSession;
import com.glines.socketio.server.SocketIOSession.SessionTransportHandler;
import com.glines.socketio.server.Transport;

public abstract class XHRTransport extends AbstractHttpTransport {
	public static final String CONTINUATION_KEY =
		"com.glines.socketio.server.transport.XHRTransport.Continuation";
	private final int bufferSize;
	private final int maxIdleTime;

	protected abstract class XHRSessionHelper
			implements SessionTransportHandler, ContinuationListener {
		protected final SocketIOSession session;
		private final TransportBuffer buffer = new TransportBuffer(bufferSize);
		private volatile boolean is_open = false;
		private volatile Continuation continuation = null;
		private final boolean isConnectionPersistant;
		private boolean disconnectWhenEmpty = false;

		XHRSessionHelper(SocketIOSession session, boolean isConnectionPersistant) {
			this.session = session;
			this.isConnectionPersistant = isConnectionPersistant;
			if (isConnectionPersistant) {
				session.setHeartbeat(HEARTBEAT_DELAY);
				session.setTimeout(HEARTBEAT_TIMEOUT);
			} else {
				session.setTimeout((HTTP_REQUEST_TIMEOUT-REQUEST_TIMEOUT)/2);
			}
		}

		protected abstract void startSend(HttpServletResponse response) throws IOException;

		protected abstract void writeData(ServletResponse response, String data) throws IOException;

		protected abstract void finishSend(ServletResponse response) throws IOException;
		
		@Override
		public void disconnect() {
			synchronized (this) {
				session.onDisconnect(DisconnectReason.DISCONNECT);
				abort();
			}
		}

		@Override
		public void close() {
			synchronized (this) {
				session.startClose();
			}
		}

		@Override
		public ConnectionState getConnectionState() {
			return session.getConnectionState();
		}

		@Override
		public void sendMessage(SocketIOFrame frame)
				throws SocketIOException {
			synchronized (this) {
				Log.debug("Session["+session.getSessionId()+"]: " +
						"sendMessage(frame): [" + frame.getFrameType() + "]: " + frame.getData());
				if (is_open) {
					if (continuation != null) {
						List<String> messages = buffer.drainMessages();
						messages.add(frame.encode());
						StringBuilder data = new StringBuilder();
						for (String msg: messages) {
							data.append(msg);
						}
						try {
							writeData(continuation.getServletResponse(), data.toString());
						} catch (IOException e) {
							throw new SocketIOException(e);
						}
						if (!isConnectionPersistant && !continuation.isInitial()) {
							Continuation cont = continuation;
							continuation = null;
							cont.complete();
						} else {
							session.startHeartbeatTimer();
						}
					} else {
						String data = frame.encode();
						if (buffer.putMessage(data, maxIdleTime) == false) {
							session.onDisconnect(DisconnectReason.TIMEOUT);
							abort();
							throw new SocketIOException();
						}
					}
				} else {
					throw new SocketIOClosedException();
				}
			}
		}

		@Override
		public void sendMessage(String message) throws SocketIOException {
			Log.debug("Session["+session.getSessionId()+"]: " +
					"sendMessage(String): " + message);
			sendMessage(SocketIOFrame.TEXT_MESSAGE_TYPE, message);
		}

		@Override
		public void sendMessage(int messageType, String message)
				throws SocketIOException {
			synchronized (this) {
				Log.debug("Session["+session.getSessionId()+"]: " +
						"sendMessage(int, String): [" + messageType + "]: " + message);
				if (is_open && session.getConnectionState() == ConnectionState.CONNECTED) {
					sendMessage(new SocketIOFrame(SocketIOFrame.FrameType.DATA, messageType, message));
				} else {
					throw new SocketIOClosedException();
				}
			}
		}

		@Override
		public void handle(HttpServletRequest request,
				HttpServletResponse response, SocketIOSession session)
				throws IOException {
			if ("GET".equals(request.getMethod())) {
				synchronized (this) {
					if (!is_open && buffer.isEmpty()) {
						response.sendError(HttpServletResponse.SC_NOT_FOUND);
					} else {
						/*
						 */
						Continuation cont = (Continuation)request.getAttribute(CONTINUATION_KEY);
						if (continuation != null || cont != null) {
							if (continuation == cont) {
								continuation = null;
								finishSend(response);
							}
							if (cont != null) {
								request.removeAttribute(CONTINUATION_KEY);
							}
							return;
						}
						if (!isConnectionPersistant) {
							if (!buffer.isEmpty()) {
								List<String> messages = buffer.drainMessages();
								if (messages.size() > 0) {
									StringBuilder data = new StringBuilder();
									for (String msg: messages) {
										data.append(msg);
									}
									startSend(response);
									writeData(response, data.toString());
									finishSend(response);
									if (!disconnectWhenEmpty) {
										session.startTimeoutTimer();
									} else {
										abort();
									}
								}
							} else {
								session.clearTimeoutTimer();
								request.setAttribute(SESSION_KEY, session);
								response.setBufferSize(bufferSize);
								continuation = ContinuationSupport.getContinuation(request);
								continuation.addContinuationListener(this);
								continuation.setTimeout(REQUEST_TIMEOUT);
								continuation.suspend(response);
								request.setAttribute(CONTINUATION_KEY, continuation);
								startSend(response);
							}
						} else {
							response.sendError(HttpServletResponse.SC_NOT_FOUND);
						}
					}
				}
			} else if ("POST".equals(request.getMethod())) {
				if (is_open) {
					int size = request.getContentLength();
					BufferedReader reader = request.getReader();
					if (size == 0) {
						response.sendError(HttpServletResponse.SC_BAD_REQUEST);
					} else {
						String data = decodePostData(request.getContentType(), IO.toString(reader));
						if (data != null && data.length() > 0) {
							List<SocketIOFrame> list = SocketIOFrame.parse(data);
							synchronized (session) {
								for (SocketIOFrame msg: list) {
									session.onMessage(msg);
								}
							}
						}
                        // Ensure that the disconnectWhenEmpty flag is obeyed in the case where
                        // it is set during a POST.
                        synchronized (this) {
                            if (disconnectWhenEmpty && buffer.isEmpty()) {
                                if (session.getConnectionState() == ConnectionState.CLOSING) {
                                    session.onDisconnect(DisconnectReason.CLOSED);
                                }
                                abort();
                            }
                        }
					}
				}
			} else {
	    		response.sendError(HttpServletResponse.SC_BAD_REQUEST);
			}
			
		}

		protected String decodePostData(String contentType, String data) {
			if (contentType.startsWith("application/x-www-form-urlencoded")) {
				if (data.substring(0, 5).equals("data=")) {
					return URIUtil.decodePath(data.substring(5));
				} else {
					return "";
				}
			} else if (contentType.startsWith("text/plain")) {
				return data;
			} else {
				// TODO: Treat as text for now, maybe error in the future.
				return data;
			}
		}
		
		@Override
		public void onComplete(Continuation cont) {
			if (continuation != null && cont == continuation) {
				continuation = null;
				if (isConnectionPersistant) {
					is_open = false;
					if (!disconnectWhenEmpty) {
						session.onDisconnect(DisconnectReason.DISCONNECT);
					}
					abort();
				} else {
					if (!is_open && buffer.isEmpty() && !disconnectWhenEmpty) {
						session.onDisconnect(DisconnectReason.DISCONNECT);
						abort();
					} else {
						if (disconnectWhenEmpty) {
							abort();
						} else {
							session.startTimeoutTimer();
						}
					}
				}
			}
		}

		@Override
		public void onTimeout(Continuation cont) {
			if (continuation != null && cont == continuation) {
				continuation = null;
				if (isConnectionPersistant) {
					is_open = false;
					session.onDisconnect(DisconnectReason.TIMEOUT);
					abort();
				} else {
					if (!is_open && buffer.isEmpty()) {
						session.onDisconnect(DisconnectReason.DISCONNECT);
						abort();
					} else {
						try {
							finishSend(cont.getServletResponse());
						} catch (IOException e) {
							session.onDisconnect(DisconnectReason.DISCONNECT);
							abort();
						}
					}
					session.startTimeoutTimer();
				}
			}
		}

		protected abstract void customConnect(HttpServletRequest request,
				HttpServletResponse response) throws IOException;
		
		public void connect(HttpServletRequest request,
				HttpServletResponse response) throws IOException {
			request.setAttribute(SESSION_KEY, session);
			response.setBufferSize(bufferSize);
			continuation = ContinuationSupport.getContinuation(request);
			continuation.addContinuationListener(this);
			if (isConnectionPersistant) {
				continuation.setTimeout(0);
			}
			customConnect(request, response);
			is_open = true;
			session.onConnect(this);
			finishSend(response);
            if (continuation != null) {
                if (isConnectionPersistant) {
                    request.setAttribute(CONTINUATION_KEY, continuation);
                    continuation.suspend(response);
                } else {
                    continuation = null;
                }
            }
		}

		@Override
		public void disconnectWhenEmpty() {
			disconnectWhenEmpty = true;
		}
		
		@Override
		public void abort() {
			session.clearHeartbeatTimer();
			session.clearTimeoutTimer();
			is_open = false;
			if (continuation != null) {
				Continuation cont = continuation;
				continuation = null;
				if (cont.isSuspended()) {
					cont.complete();
				}
			}
			buffer.setListener(new TransportBuffer.BufferListener() {
				@Override
				public boolean onMessages(List<String> messages) {
					return false;
				}

				@Override
				public boolean onMessage(String message) {
					return false;
				}
			});
			buffer.clear();
			session.onShutdown();
		}
	}

	public XHRTransport(int bufferSize, int maxIdleTime) {
		this.bufferSize = bufferSize;
		this.maxIdleTime = maxIdleTime;
	}

	/**
	 * This method should only be called within the context of an active HTTP request.
	 */
	protected abstract XHRSessionHelper createHelper(SocketIOSession session);
	
	@Override
	protected SocketIOSession connect(HttpServletRequest request,
			HttpServletResponse response, Transport.InboundFactory inboundFactory,
			com.glines.socketio.server.SocketIOSession.Factory sessionFactory)
			throws IOException {
		SocketIOInbound inbound = inboundFactory.getInbound(request);
		if (inbound != null) {
 			SocketIOSession session = sessionFactory.createSession(inbound);
			XHRSessionHelper handler =  createHelper(session);
			handler.connect(request, response);
			return session;
		}
		return null;
	}

}
