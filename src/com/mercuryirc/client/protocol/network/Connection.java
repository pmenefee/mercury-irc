package com.mercuryirc.client.protocol.network;

import com.mercuryirc.client.protocol.model.*;

import javax.net.ssl.*;
import java.io.*;
import java.net.Socket;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

/**
 * The IRC model is based entirely on responses from the server to prevent
 * client-server inconsistencies. For example, the joinChannel() method only
 * sends the request to the server to join the channel; it does not add
 * a new Channel object to the Server until the response is received from
 * the server saying that the channel was successfully joined.
 */
public class Connection implements Runnable {
	private Server server;

	private boolean acceptAllCerts;

	private Socket socket;
	private BufferedReader in;
	private BufferedWriter out;

	private ExceptionHandler exceptionHandler;

	public Connection(Server server) {
		this.server = server;
	}

	public Server getServer() {
		return server;
	}

	public void connect() {
		try {
			if(server.isSsl()) {
				SSLSocketFactory ssf;

				if(acceptAllCerts)
					ssf = getLenientSocketFactory();
				else
					ssf = (SSLSocketFactory) SSLSocketFactory.getDefault();

				socket = ssf.createSocket(server.getHost(), server.getPort());
			} else {
				socket = new Socket(server.getHost(), server.getPort());
			}

			in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
			out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));

			new Thread(this).start();
		} catch(IOException e) {
			if(exceptionHandler != null)
				exceptionHandler.onException(e);
		}
	}

	/**
	 * Users of this class should not call this method directly,
	 * use connect() instead.
	 */
	public void run() {
		for(String line = readLine(); line != null; line = readLine()) {
			String[] parts = line.split(" ");

			String command;
			if(line.startsWith(":"))
				command = parts[1];
			else
				command = parts[0];

			try {
				int numeric = Integer.parseInt(command);

				for(NumericHandler nh : NumericHandlers.list)
					if(nh.appliesTo(numeric))
						nh.process(line, parts, this);
			} catch(NumberFormatException e) {
				// not a numeric
				for(CommandHandler lh : CommandHandlers.list)
					if(lh.appliesTo(command, line))
						lh.process(line, parts, this);
			}
		}
	}

	public void joinChannel(String channel) {
		writeLine("JOIN " + channel);
	}

	public void registerAs(User user) {
		writeLine("NICK " + user.getNick());
		writeLine("USER " + user.getUser() + " * * :" + user.getRealName());
	}

	public void disconnect() {
		try {
			socket.close();
		} catch(IOException e) {
			if(exceptionHandler != null)
				exceptionHandler.onException(e);
		}
	}

	public void writeLine(String rawLine) {
		try {
			System.out.println("(out) " + rawLine);
			out.write(rawLine + "\r\n");
			out.flush();
		} catch(IOException e) {
			if(exceptionHandler != null)
				exceptionHandler.onException(e);
		}
	}

	public String readLine() {
		try {
			String s = in.readLine();
			System.out.println(" (in) " + s);
			return s;
		} catch(IOException e) {
			if(exceptionHandler != null)
				exceptionHandler.onException(e);
			return null;
		}
	}

	public void setExceptionHandler(ExceptionHandler exceptionHandler) {
		this.exceptionHandler = exceptionHandler;
	}

	public void setAcceptAllSSLCerts(boolean b) {
		acceptAllCerts = b;
	}

	private SSLSocketFactory getLenientSocketFactory() {
		TrustManager[] trustAllCerts = new TrustManager[]{ new X509TrustManager() {
			public X509Certificate[] getAcceptedIssuers(){ return null; }
			public void checkClientTrusted(X509Certificate[] certs, String authType){ }
			public void checkServerTrusted(X509Certificate[] certs, String authType){ }
		}};

		try {
			SSLContext sc = SSLContext.getInstance("TLS");
			sc.init(null, trustAllCerts, new SecureRandom());
			return sc.getSocketFactory();
		} catch (Exception e) {
			e.printStackTrace();
		}

		return null;
	}

	public static interface ExceptionHandler {
		public void onException(Exception e);
	}

	public static interface CommandHandler {
		public boolean appliesTo(String command, String line);
		public void process(String line, String[] parts, Connection conn);
	}

	public static interface NumericHandler {
		public boolean appliesTo(int numeric);
		public void process(String line, String[] parts, Connection conn);
	}
}