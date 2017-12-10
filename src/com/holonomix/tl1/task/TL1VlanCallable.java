package com.holonomix.tl1.task;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import org.apache.log4j.Logger;

import com.holonomix.exception.AdapterException;
import com.holonomix.exception.TopologyException;
import com.holonomix.hsqldb.model.Card;
import com.holonomix.hsqldb.model.Device;
import com.holonomix.hsqldb.model.Ipam;
import com.holonomix.hsqldb.model.Port;
import com.holonomix.hsqldb.model.VLan;
import com.holonomix.properties.PropertiesContainer;
import com.holonomix.tl1.ManagerTelnet;
import com.holonomix.tl1.TelnetService;
import com.holonomix.tl1.utility.UtilityTL1;

public class TL1VlanCallable implements Callable<VLan> {
	private static final Logger log = Logger.getLogger(TL1VlanCallable.class);

	PropertiesContainer propertiesContainer;
	TelnetService telnetService;
	boolean b_discover_only_ethernet;
	
	UtilityTL1 utilityTL1;
	VLan vlan;
	Ipam ipam;
	public TL1VlanCallable(Ipam ipam,VLan vlan) {

		this.propertiesContainer = PropertiesContainer.getInstance();
		this.b_discover_only_ethernet = Boolean.getBoolean(propertiesContainer
				.getProperty("TOPOLOGYUPDATE_DOWNSTREAM"));
		
		utilityTL1 = new UtilityTL1();
		this.vlan = vlan;
		this.ipam = ipam;
	}

	public VLan call() throws AdapterException {
		Thread.currentThread().setName("TL1VlanCallable");
		ManagerTelnet managerTelnet = ManagerTelnet.getInstance();
		try {
			telnetService = managerTelnet.getConnection();
		} catch (InterruptedException e) {
			log.error("interrupted exception");
			throw new AdapterException();
		}
		try {
			
			processVlan();
		} catch (TopologyException e) {
			log.error("error in tl1");
			telnetService=null;
			throw new  AdapterException();
		}
		finally{
			managerTelnet.returnConnection(telnetService);
		}
		
		
		
		return vlan;
	}

	private void processVlan() throws TopologyException {
		try {
			String tagId = "ID0008";
			
			String str_vlan_id = vlan.getVLANkey().trim();
			log.debug("RESEARCH VLAN: " + str_vlan_id);

			
			for(String deviceId : vlan.getDeviceNameList()) {
				
				String str_vlan_port_command = "RTRV-LANXVLANPORT:"+deviceId+":ALL:"+tagId+"::"+str_vlan_id+";";
			String str_result_vlanports = telnetService
					.sendCommand(str_vlan_port_command,tagId);
			parseResultPortOfVlanOfDevice(str_result_vlanports,deviceId,str_vlan_id);
			}
		}

		catch (TopologyException e) {
			log.error("impossible create a connection with the server");
			throw e;

		}

	}
	
	private void parseResultPortOfVlanOfDevice(String str_result_vlans,String deviceId,String vlanId)throws TopologyException{
		String str = "";
		try {
			StringReader reader = new StringReader(str_result_vlans);
			BufferedReader bf = new BufferedReader(reader);
			boolean b_can_read = false;
			log.debug("output : "+str_result_vlans);

			do {
				str = bf.readLine();
				if (str != null && str.indexOf("COMPLD") != -1) {
					b_can_read = true;
					bf.readLine();
					break;
				}
			} while (str != null);

			if (b_can_read) {

				do {
					str = "";
					do {
						str += bf.readLine();
						//davide alu
					} while (str != null && !str.endsWith("\"")
							&& !str.endsWith("null"));
					if (str != null && !str.equalsIgnoreCase("null")) {
						String[] items = str.split(":");

						if (items.length == 2 && items[1].indexOf(vlanId)!=-1) {
							Port port = new Port();
							port.setTag(items[0].trim().replace("\"", ""));
							findPort(port, deviceId);
							

						} else {
							log
									.debug("parseResultPortOfVlanOfDevice: line decomposed is NOT 2 tokens or vlanId does not match: "
											+ str);
						}

					}
				} while (str != null && !str.equalsIgnoreCase("null"));
			}
		} catch (Exception e) {

			log.error("TL1: parseResultPortOfVlanOfDevice: error while parsing "
					+ str);
			throw new TopologyException();
		}

	}

	

private void findPort(Port port,String deviceId) {
		
		boolean found = false;
		for (Device deviceNew :ipam.getDeviceList())
		if (deviceNew!=null) {
		if ((deviceNew.getName()+"").equalsIgnoreCase(deviceId)){
			
			for (Card card:deviceNew.getCardList()) {
				
				if (card.getName().equalsIgnoreCase("1/10")||card.getName().equalsIgnoreCase("1/12")  ){
					for (Port portTemp:card.getPortList()){
						
						if (portTemp.getTag().equalsIgnoreCase(port.getTag()) ){
							VLan vlanClone = vlan.clone();
							String vLANkey = vlan.getVLANkey().replace("LANXVLAN-", "");
							vlanClone.setVLANkey(vLANkey);
							portTemp.getVlanList().add(vlanClone);
							found = true;
							log.debug("vlan "+vlan.getVLANkey()+ " added to port "+ portTemp.getName());
							return;
						}
					}
				}
				}}
			
		}
		if (found==false){
			log.debug("vlan "+vlan.getVLANkey()+ " not found because port  "+ port.getTag() +" is not present.");
		}	
			
		
	}

}
