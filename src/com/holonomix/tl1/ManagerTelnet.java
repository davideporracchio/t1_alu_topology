package com.holonomix.tl1;

import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.Logger;

import com.holonomix.exception.AdapterException;
import com.holonomix.exception.TopologyException;
import com.holonomix.properties.PropertiesContainer;

public class ManagerTelnet {
	private List<TelnetService> pool;
	private static ManagerTelnet managerTelnet;
	private PropertiesContainer propertiesContainer;
	private int maxConnections = 3;
	private int minutesHold = 1;
	private int sizepool=0;
	private static final  Logger log = Logger.getLogger(ManagerTelnet.class);

	public static ManagerTelnet getInstance()
			throws AdapterException {
		if (managerTelnet == null) {

			managerTelnet = new ManagerTelnet();
		}
		return managerTelnet;
	}

	private ManagerTelnet()  {
		propertiesContainer = PropertiesContainer.getInstance();

	}

	public void createConnections()
				throws AdapterException {
		
		
		maxConnections = Integer.parseInt(propertiesContainer.getProperty("TOPOLOGY_RETRY_NUMBER"));
		minutesHold = Integer.parseInt(propertiesContainer.getProperty("TOPOLOGY_RETRY_HOLDTIME"));
		int numberThreads = Integer.parseInt(propertiesContainer.getProperty("NUMBER_THREADS"));
		pool = new ArrayList<TelnetService>(numberThreads);
		for (int i = 0; i < numberThreads; i++) {
			TelnetService telnetService = checkConnection(null);
			if (telnetService!=null && telnetService.isConnected()){
				pool.add(telnetService);
				sizepool++;
			}
			else {
				
				break;
			}
				
		}
	}

	public void disconnectConnections() {
		
		try {
			if (pool==null ) return;
			for (TelnetService telnetService : pool) {
				log.debug("close connection");
				if (telnetService!=null)
				telnetService.disconnect();
			}

		} catch (TopologyException e) {
			
			log.error("error closing connection");
		}
		log.debug(" close all connections in TL1");

	}

	public TelnetService getConnection() throws InterruptedException {
		if(sizepool>0){
		synchronized (pool) {
			while (pool.isEmpty()) {
				pool.wait();
			}
			return pool.remove(0);
		}
		}
		else return null;
	}

	private TelnetService checkConnection(TelnetService telnetService)
			throws AdapterException {
		int nummberConnection = 0;
		while ((telnetService == null || !telnetService.isConnected())
				&& nummberConnection < maxConnections) {
			try {
				telnetService = new TelnetService();

				telnetService.connect();
			} catch (TopologyException e) {
				
				log.error("try to estabilish new connection");
				nummberConnection++;
				telnetService=null;
				try {
					Thread.sleep(minutesHold * 1000);
				} catch (InterruptedException e2) {
					
					log.error("interrupted exception");
				}
			}

		}

		return telnetService;
	}

	public void returnConnection(TelnetService telnetService)
			throws AdapterException {
		telnetService = checkConnection(telnetService);

		synchronized (pool) {
			pool.add(telnetService);
			pool.notify();
		}

	}

}