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

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.glines.socketio.server.SocketIOInbound;
import com.glines.socketio.server.SocketIOSession;
import com.glines.socketio.server.SocketIOSession.SessionTransportHandler;

public abstract class AbstractHttpTransport extends AbstractTransport {

	public AbstractHttpTransport() {
	}

	protected abstract SocketIOSession connect(
			HttpServletRequest request,
			HttpServletResponse response,
			SocketIOInbound.Factory inboundFactory,
			SocketIOSession.Factory sessionFactory) throws IOException;

	@Override
	public void handle(HttpServletRequest request,
			HttpServletResponse response,
			SocketIOInbound.Factory inboundFactory,
			SocketIOSession.Factory sessionFactory)
			throws IOException {

		String sessionId = extractSessionId(request);

		if ("GET".equals(request.getMethod()) && sessionId == null) {
 			SocketIOSession session = connect(request, response, inboundFactory, sessionFactory);
 			if (session == null) {
 				response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
 			}
		} else {
			SocketIOSession session = sessionFactory.getSession(sessionId);
			if (session != null) {
				SessionTransportHandler handler = session.getTransportHandler();
				if (handler != null) {
					handler.handle(request, response, session);
				} else {
					session.onShutdown();
		    		response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
				}
			} else {
	    		response.sendError(HttpServletResponse.SC_FORBIDDEN);
			}
		}
	}
}
