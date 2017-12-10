package com.holonomix.tl1;

import java.io.InputStream;
import java.io.PrintStream;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.commons.net.telnet.TelnetClient;
import org.apache.log4j.Logger;

import com.holonomix.exception.TopologyException;
import com.holonomix.hsqldb.model.utility.EncryptDecrypt;
import com.holonomix.properties.PropertiesContainer;

public class TelnetService {
	private TelnetClient telnetClient;

	private InputStream in;
	private PrintStream out;
	
	private PropertiesContainer propertiesContainer;

	private static final Logger log = Logger.getLogger(TelnetService.class);

	public TelnetService() {
		propertiesContainer = PropertiesContainer.getInstance();
		telnetClient = new TelnetClient();

		// Connect to the specified server
	}

	public void connect() throws TopologyException {
		try {
			String server = propertiesContainer.getProperty("EMS_IP");
			String port = propertiesContainer.getProperty("TOPOLOGY_PORT");
			String user = propertiesContainer.getProperty("TOPOLOGY_USERNAME");
			String password = propertiesContainer
					.getProperty("TOPOLOGY_PASSWORD");
			password = EncryptDecrypt.decryptPassword(password);
			log.debug("port " + port);
			telnetClient.connect(server, Integer.parseInt(port));
			in = telnetClient.getInputStream();
			out = new PrintStream(telnetClient.getOutputStream());
			if (user != null && !user.equalsIgnoreCase("")) {
				write("ACT-USER:TNM1:" + user + ":1::" + password + ";");
				readBuffer("Log In");
			}

		} catch (Exception e) {
			log.error(e.getMessage());
			throw new TopologyException(" error in starting connection ");
		}
	}

	public boolean isConnected() {

		return telnetClient.isConnected();
	}

	public String sendCommand(String command, String tagId)
			throws TopologyException {
		try {
			write(command);
			String output = readBuffer(tagId);
			// if (output.equalsIgnoreCase("")){
			// output = readUntil(tagId);
			// }
			return output;
		} catch (Exception e) {

			throw new TopologyException();
		}
	}

	public void disconnect() throws TopologyException {

		try {
			telnetClient.disconnect();
		} catch (Exception e) {
			log.error(e.getMessage());
			throw new TopologyException();
		}
	}

	private void write(String value) throws TopologyException {
		try {
			//dont write password on log file
			if (value.indexOf("ACT-USER") == -1)
				log.debug(" sending :" + value);
			out.println(value);
			out.flush();

		} catch (Exception e) {
			log.error(e.getMessage());
			throw new TopologyException();
		}
	}

	/**
	 * This function returns what we receive from NBI
	 * return empty string if we do not receive the tagid we expect or there are other issue
	 * A topologyException can be rise if we do not receive a message after 5 seconds
	 * */
	private String readUntil(String tagId) throws TopologyException {

		
		StringBuffer sb = new StringBuffer();
		
		boolean stop = false;
		try {

			char ch =(char) in.read();

			while (ch != -1 && ch != 0 && ((char) ch != ';') && stop == false) {
				sb.append((char) ch);

				ch = (char)in.read();

				if ((char) ch == ';') {
					
					stop = true;
				}
			}
			
			if (sb.length() == 0)
				throw new TopologyException();
			else if (sb.indexOf("Status, Maximum OSS Session Limit Reached") != -1) {
				log.error("Status, Maximum OSS Session Limit Reached");
				throw new TopologyException();
			}
		} catch (RuntimeException e) {
			log.error(" buffer empty:" + e.getMessage());
			throw new TopologyException();

		} catch (Exception e) {
			log.error(" buffer empty:" + e.getMessage());
			throw new TopologyException();
		}
		if (sb.indexOf("DENY") != -1
				&& (tagId.equalsIgnoreCase("Log In") || tagId
						.equalsIgnoreCase("ID0004"))) {
			log.error("Authentication issue");
			throw new TopologyException();
		}
		if (sb.indexOf(tagId) != -1) {
			
		} else {
			sb = new StringBuffer();
		}

		return sb.toString();
	}

	
	/***
	 * 
	 * This function is used to read a message if we do not receive it in 5 seconds we terminate the thread
	 * and send a topologyException
	 * 
	 * */
	private String readBuffer(final String tagId) throws TopologyException {

		
		String result=null;
		Callable<String> callReadBuffer = new Callable<String>() {
			public String call() {
				try {
					String s = readUntil(tagId);
					return s;
				} catch (Exception e) {
					
					return null;
				}
			}
		};
		ExecutorService exectutor = Executors
				.newCachedThreadPool();
		try {
		final Future<String> handler = exectutor.submit(callReadBuffer);
		
		exectutor.shutdown();
			result = handler.get(Long.parseLong(propertiesContainer.getProperty("TL1_READ_TIMEOUT")), TimeUnit.SECONDS);
			if (result == null)
				throw new TopologyException(" TL1_READ_TIMEOUT not set" );
			
		} catch (Exception ex) {
			log.error("TIMEOUT issue. Try to increase the value of TL1_READ_TIMEOUT property");
			
		}

		return result;

	}

}