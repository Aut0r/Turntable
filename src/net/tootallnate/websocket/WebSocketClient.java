package net.tootallnate.websocket;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;

import net.tootallnate.websocket.drafts.Draft_10;

/**
 * The <tt>WebSocketClient</tt> is an abstract class that expects a valid
 * "ws://" URI to connect to. When connected, an instance recieves important
 * events related to the life of the connection. A subclass must implement
 * <var>onOpen</var>, <var>onClose</var>, and <var>onMessage</var> to be useful.
 * An instance can send messages to it's connected server via the
 * <var>send</var> method.
 * 
 * @author Nathan Rajlich
 */
@SuppressWarnings("unused")
public abstract class WebSocketClient extends WebSocketAdapter implements
		Runnable {

	/**
	 * The URI this client is supposed to connect to.
	 */
	private URI uri = null;
	/**
	 * The WebSocket instance this client object wraps.
	 */
	private WebSocket conn = null;
	/**
	 * The SocketChannel instance this client uses.
	 */
	private SocketChannel client = null;
	/**
	 * The 'Selector' used to get event keys from the underlying socket.
	 */
	private Selector selector = null;
	/**
	 * Keeps track of whether or not the client thread should continue running.
	 */
	private boolean running = false;
	/**
	 * The Draft of the WebSocket protocol the Client is adhering to.
	 */
	private Draft draft = null;
	/**
	 * Number 1 used in handshake
	 */
	private int number1 = 0;
	/**
	 * Number 2 used in handshake
	 */
	private int number2 = 0;
	/**
	 * Key3 used in handshake
	 */
	private byte[] key3 = null;

	// CONSTRUCTORS ////////////////////////////////////////////////////////////
	public WebSocketClient(String serverURI) {
		this(getURI(serverURI), new Draft_10());
	}

	public static URI getURI(String url) {
		try {
			return new URI(url);
		} catch (Exception e) {
		}
		return null;
	}

	public WebSocketClient(URI serverURI) {
		this(serverURI, new Draft_10());
	}

	/**
	 * Constructs a WebSocketClient instance and sets it to the connect to the
	 * specified URI. The client does not attampt to connect automatically. You
	 * must call <var>connect</var> first to initiate the socket connection.
	 * 
	 * @param serverUri
	 *            The <tt>URI</tt> of the WebSocket server to connect to.
	 * @throws IllegalArgumentException
	 *             If <var>draft</var> is <code>Draft.AUTO</code>
	 */
	public WebSocketClient(URI serverUri, Draft draft) {
		this.uri = serverUri;
		this.draft = draft;
	}

	/**
	 * Gets the URI that this WebSocketClient is connected to.
	 * 
	 * @return The <tt>URI</tt> for this WebSocketClient.
	 */
	public URI getURI() {
		return uri;
	}

	public Draft getDraft() {
		return draft;
	}

	/**
	 * Starts a background thread that attempts and maintains a WebSocket
	 * connection to the URI specified in the constructor or via
	 * <var>setURI</var>. <var>setURI</var>.
	 */
	public void connect() {
		if (!running) {
			this.running = true;
			(new Thread(this)).start();
		}
	}

	/**
	 * Calls <var>close</var> on the underlying SocketChannel, which in turn
	 * closes the socket connection, and ends the client socket thread.
	 * 
	 * @throws IOException
	 *             When socket related I/O errors occur.
	 */
	public void close() {
		try {
			if (running) {
				running = false;
				if (selector != null && conn != null) {
					selector.wakeup();
					conn.close();
				} else {
					onClose(conn);
				}
			}
		} catch (Exception ex) {

		}
	}

	/**
	 * Sends <var>text</var> to the connected WebSocket server.
	 * 
	 * @param text
	 *            The String to send to the WebSocket server.
	 * @throws IOException
	 *             When socket related I/O errors occur.
	 */
	public void send(String text) {
		try {
			if (conn != null) {
				conn.send(text);
			}
		} catch (Exception ex) {

		}
	}

	/**
	 * Reinitializes and prepares the class to be used for reconnect.
	 * 
	 * @return
	 */
	public void releaseAndInitialize() {
		conn = null;
		client = null;
		selector = null;
		running = false;
		draft = null;
		number1 = 0;
		number2 = 0;
		key3 = null;
	}

	private boolean tryToConnect(InetSocketAddress remote) {
		try {
			client = SocketChannel.open();
			client.configureBlocking(false);
			client.connect(remote);

			selector = Selector.open();

			this.conn = new WebSocket(client,
					new LinkedBlockingQueue<ByteBuffer>(), this, draft,
					Integer.MAX_VALUE);
			if (client != null) {
				client.register(selector, SelectionKey.OP_CONNECT);
			}

		} catch (IOException ex) {
			onIOError(conn, ex);
			return false;
		}

		return true;
	}

	public void run() {
		running = tryToConnect(new InetSocketAddress(uri.getHost(), getPort()));

		while (this.running) {
			SelectionKey key = null;
			try {
				selector.select();
				Set<SelectionKey> keys = selector.selectedKeys();
				Iterator<SelectionKey> i = keys.iterator();

				while (i.hasNext()) {
					key = i.next();
					i.remove();

					if (key.isConnectable()) {
						finishConnect();
					}

					if (key.isReadable()) {
						conn.handleRead();
					}
				}
			} catch (Exception ex) {
				if (key != null)
					key.cancel();
				onError(ex);
			}
		}

		// System.err.println("WebSocketClient thread ended!");
	}

	private int getPort() {
		int port = uri.getPort();
		return port == -1 ? WebSocket.DEFAULT_PORT : port;
	}

	private void finishConnect() throws Exception {
		if (client.isConnectionPending()) {
			client.finishConnect();
		}

		// Now that we're connected, re-register for only 'READ' keys.
		client.register(selector, SelectionKey.OP_READ);

		sendHandshake();
	}

	private void sendHandshake() throws Exception {
		String path = uri.getPath();
		if (path.indexOf("/") != 0) {
			path = "/" + path;
		}
		int port = getPort();
		String host = uri.getHost()
				+ (port != WebSocket.DEFAULT_PORT ? ":" + port : "");
		String origin = "x"; // TODO: Make 'origin' configurable

		HandshakedataImpl1 handshake = new HandshakedataImpl1();
		handshake.setResourceDescriptor(path);
		handshake.put("Host", host);
		handshake.put("Origin", origin);
		conn.startHandshake(handshake);
	}

	/**
	 * Calls subclass' implementation of <var>onMessage</var>.
	 * 
	 * @param conn
	 * @param message
	 */
	public void onMessage(WebSocket conn, String message) {
		onMessage(message);
	}

	/**
	 * Calls subclass' implementation of <var>onClose</var>.
	 * 
	 * @param conn
	 */
	public void onClose(WebSocket conn) {
		releaseAndInitialize();
	}

	/**
	 * Calls subclass' implementation of <var>onIOError</var>.
	 * 
	 * @param conn
	 */
	public void onIOError(WebSocket conn, IOException ex) {
		releaseAndInitialize();
	}

	public abstract void onMessage(String message);

}
