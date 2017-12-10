package com.holonomix.tl1.utility;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.holonomix.hsqldb.model.Card;
import com.holonomix.hsqldb.model.Chassis;
import com.holonomix.hsqldb.model.Device;
import com.holonomix.hsqldb.model.Port;
import com.holonomix.hsqldb.model.SNMPAgent;

public class UtilityTL1 {

	
	
	public static void  duplicateEntries(Map<String,Device> mapDevices,int size){
		List<Device> deviceList = new ArrayList<Device>(mapDevices.values());
		for (Device device :deviceList ){
			for (int numberId=0;numberId<size;numberId++){
			
			
			Device newDevice = new Device();
			
			newDevice.setName(changeName(device.getName(),numberId+""));
			mapDevices.put(newDevice.getName(), newDevice);
			newDevice.setCreationClassName(device.getCreationClassName());
			newDevice.setType(device.getType());
			for (Chassis chassis: device.getChassisList()){
				Chassis newChassis= new Chassis();
				newChassis.setName(changeName(chassis.getName(),numberId+""));
				newDevice.getChassisList().add(newChassis);
			}
			for (SNMPAgent snmpAgent: device.getSnmpAgentList()){
				SNMPAgent newSNMPAgent= new SNMPAgent();
				newSNMPAgent.setName(changeName(snmpAgent.getName(),numberId+""));
				newDevice.getSnmpAgentList().add(newSNMPAgent);
			}
			
			for (Card card: device.getCardList()){
							
				Card newCard = new Card();
				newCard.setId(card.getId());
				newCard.setShelfNumber(card.getShelfNumber());
				newCard.setSlotNumber(card.getSlotNumber());
				newCard.setName(card.getName());
				newCard.setDescription(card.getDescription());
				newCard.setType(card.getType());
				newDevice.getCardList().add(newCard);
				for (Port port: card.getPortList()){
					
					Port newPort = new Port();
					newPort.setId(card.getId());
					newPort.setShelfNumber(card.getShelfNumber());
					newPort.setSlotNumber(card.getSlotNumber());
					newPort.setPortNumber(port.getPortNumber());
					newPort.setName(card.getName());
					
					newPort.setDescription(card.getDescription());
					newPort.setPortKey(port.getPortKey());
					newPort.setPortType(port.getPortType());
					newPort.setAdminStatus(port.getAdminStatus());
					newPort.setMaxSpeed(port.getMaxSpeed());

					newCard.getPortList().add(newPort);
					
					
				}
			}
			}
		}
		
	}
	
	private static String changeName(String name,String append){
		String newName="";
		if (name.indexOf("/")!=-1)
			newName = name.substring(0,name.indexOf("/"))+append+name.substring(name.indexOf("/")+1);
		else
			newName= name+append;
		
		
		return newName;
	}
	
	
	
	public ArrayList<String> decompose(String sbString, String str_delimiter) {
		
		String newstr = sbString + str_delimiter;
		StringBuffer sb = new StringBuffer(newstr);
		ArrayList<String> v = new ArrayList<String>();
		String str;
		int i;
		int index = 0;
		do {
			i = sb.indexOf(str_delimiter, index);
			
			if (i != (-1)) {
				str = sb.substring(index, i);
				
				v.add(str);
				index = i + 1;
			}
		} while (i != (-1));
		return v;
	}
	
	
	

	

	
}
