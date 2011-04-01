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
package com.glines.socketio.sample.gwtchat;

import java.io.IOException;
import java.util.Collections;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.http.HttpServletRequest;

import com.glines.socketio.server.SocketIOOutbound;
import org.eclipse.jetty.util.ajax.JSON;
import org.eclipse.jetty.util.log.Log;

import com.glines.socketio.common.DisconnectReason;
import com.glines.socketio.common.SocketIOException;
import com.glines.socketio.server.SocketIOFrame;
import com.glines.socketio.server.SocketIOInbound;
import com.glines.socketio.server.SocketIOServlet;

public class GWTChatSocketServlet extends SocketIOServlet {
	private static final long serialVersionUID = 1L;
	private AtomicInteger ids = new AtomicInteger(1);
	private Queue<GWTChatConnection> connections = new ConcurrentLinkedQueue<GWTChatConnection>();

	private class GWTChatConnection implements SocketIOInbound {
		private volatile SocketIOOutbound outbound = null;
		private Integer sessionId = ids.getAndIncrement();

		@Override
		public void onConnect(SocketIOOutbound outbound) {
			this.outbound = outbound;
            connections.offer(this);
			try {
				outbound.sendMessage(SocketIOFrame.JSON_MESSAGE_TYPE, JSON.toString(
						Collections.singletonMap("welcome", "Welcome to GWT Chat!")));
			} catch (SocketIOException e) {
				outbound.disconnect();
			}
			broadcast(SocketIOFrame.JSON_MESSAGE_TYPE, JSON.toString(
					Collections.singletonMap("announcement", sessionId + " connected")));
		}

		@Override
		public void onDisconnect(DisconnectReason reason, String errorMessage) {
				this.outbound = null;
				connections.remove(this);
			broadcast(SocketIOFrame.JSON_MESSAGE_TYPE, JSON.toString(
					Collections.singletonMap("announcement", sessionId + " disconnected")));
		}

		@Override
		public void onMessage(int messageType, String message) {
			Log.debug("Recieved: " + message);
			if (message.equals("/rclose")) {
				outbound.close();
			} else if (message.equals("/rdisconnect")) {
				outbound.disconnect();
			} else if (message.startsWith("/sleep")) {
				int sleepTime = 1;
				String parts[] = message.split("\\s+");
				if (parts.length == 2) {
					sleepTime = Integer.parseInt(parts[1]);
				}
				try {
					Thread.sleep(sleepTime * 1000);
				} catch (InterruptedException e) {
					// Ignore
				}
				try {
					outbound.sendMessage(SocketIOFrame.JSON_MESSAGE_TYPE, JSON.toString(
							Collections.singletonMap("message","Slept for " + sleepTime + " seconds.")));
				} catch (SocketIOException e) {
					outbound.disconnect();
				}
			} else {
				broadcast(SocketIOFrame.JSON_MESSAGE_TYPE, JSON.toString(
						Collections.singletonMap("message",
								new String[]{sessionId.toString(), (String)message})));
			}
		}

		private void broadcast(int messageType, String message) {
			Log.debug("Broadcasting: " + message);
				for(GWTChatConnection c: connections) {
					if (c != this) {
						try {
							c.outbound.sendMessage(messageType, message);
						} catch (IOException e) {
							c.outbound.disconnect();
						}
					}
				}
			}
		}

	@Override
	protected SocketIOInbound doSocketIOConnect(HttpServletRequest request) {
		return new GWTChatConnection();
	}

}
