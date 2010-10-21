package com.glines.socketio;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.GenericServlet;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.glines.socketio.transport.FlashSocketTransport;
import com.glines.socketio.transport.WebSocketTransport;
import com.glines.socketio.transport.XHRPollingTransport;

/**
 */
public abstract class SocketIOServlet extends GenericServlet {
	public static final String BUFFER_SIZE_INIT_PARAM = "bufferSize";
	public static final String MAX_IDLE_TIME_INIT_PARAM = "maxIdleTime";
	public static final int BUFFER_SIZE_DEFAULT = 8192;
	public static final int MAX_IDLE_TIME_DEFAULT = 300*1000;
	private static final long serialVersionUID = 1L;
	private SocketIOSessionManager sessionManager = null;
	private Map<String, Transport> transports = new HashMap<String, Transport>();

	@Override
	public void init() throws ServletException {
		super.init();
		String str = this.getInitParameter(BUFFER_SIZE_INIT_PARAM);
		int bufferSize = str==null ? BUFFER_SIZE_DEFAULT : Integer.parseInt(str);
		str = this.getInitParameter(MAX_IDLE_TIME_INIT_PARAM);
		int maxIdleTime = str==null ? MAX_IDLE_TIME_DEFAULT : Integer.parseInt(str);

		sessionManager = new SocketIOSessionManager(bufferSize, maxIdleTime);
		WebSocketTransport wst = new WebSocketTransport(bufferSize, maxIdleTime);
//		FlashSocketTransport fst = new FlashSocketTransport(bufferSize, maxIdleTime);
		XHRPollingTransport xhrpt = new XHRPollingTransport(bufferSize, maxIdleTime);
		transports.put(wst.getName(), wst);
//		transports.put(fst.getName(), fst);
		transports.put(xhrpt.getName(), xhrpt);
		
		for (Transport t: transports.values()) {
			t.init();
		}
	}


	@Override
	public void service(ServletRequest request, ServletResponse response)
			throws ServletException, IOException {
		service((HttpServletRequest)request, (HttpServletResponse)response);
	}

    protected void service(HttpServletRequest request, HttpServletResponse response)
    		throws ServletException, IOException {
    	if ("OPTIONS".equals(request.getMethod())) {
    		return;
    	}

    	String path = request.getPathInfo();
    	if (path == null || path.length() == 0 || "/".equals(path)) {
    		response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing SocketIO transport");
    		return;
    	}
    	if (path.startsWith("/")) path = path.substring(1);
    	String[] parts = path.split("/");

    	Transport transport = transports.get(parts[0]);
    	if (transport == null) {
    		if ("GET".equals(request.getMethod()) && "socket.io.js".equals(parts[0])) {
				response.setContentType("text/javascript");
				InputStream is = this.getClass().getClassLoader().getResourceAsStream("socket.io.js");
				OutputStream os = response.getOutputStream();
				byte[] data = new byte[8192];
				int nread = 0;
				while ((nread = is.read(data)) > 0) {
					os.write(data, 0, nread);
					if (nread < data.length) {
						break;
					}
				}
				return;
    		} else {
	    		response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Unknown SocketIO transport");
	    		return;
    		}
    	}

  		transport.handle(request, response, new SocketIOInbound.Factory() {

			@Override
			public SocketIOInbound getInbound(HttpServletRequest request,
					String protocol) {
				return SocketIOServlet.this.doSocketIOConnect(request, protocol);
			}
  			
  		}, sessionManager);
    }

    @Override
    public void destroy() {
    	for (Transport t: transports.values()) {
    		t.destroy();
    	}
    	super.destroy();
    }

	protected abstract SocketIOInbound doSocketIOConnect(HttpServletRequest request, String protocol);
}