package com.holonomix.tl1.task;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.HashMap;
import java.util.HashSet;
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
import com.holonomix.hsqldb.model.utility.EncryptDecrypt;
import com.holonomix.properties.PropertiesContainer;
import com.holonomix.tl1.ManagerTelnet;
import com.holonomix.tl1.TelnetService;
import com.holonomix.tl1.utility.UtilityTL1;

public class TL1DeviceCallable implements Callable<Ipam> {
	private static final Logger log = Logger.getLogger(TL1DeviceCallable.class);

	private PropertiesContainer propertiesContainer;
	private TelnetService telnetService;
	boolean b_discover_only_ethernet;
	private Device device;
	private UtilityTL1 utilityTL1;
	private Ipam ipam;
	private int maxConnection;

	public TL1DeviceCallable(Device device) {

		this.propertiesContainer = PropertiesContainer.getInstance();
		this.b_discover_only_ethernet = false;
		if (propertiesContainer.getProperty("TOPOLOGYUPDATE_DOWNSTREAM").trim()
				.equalsIgnoreCase("TRUE"))
			this.b_discover_only_ethernet = true;
		this.device = device;
		utilityTL1 = new UtilityTL1();
		ipam = new Ipam();
		this.maxConnection = Integer.parseInt(propertiesContainer
				.getProperty("TOPOLOGY_RETRY_NUMBER"));
	}

	public Ipam call() throws AdapterException {
		Thread.currentThread().setName("DeviceCallable");
		ManagerTelnet managerTelnet = ManagerTelnet.getInstance();
		try {
			log.debug("getConnection ");
			telnetService = managerTelnet.getConnection();
		} catch (InterruptedException e) {
			log.error("interrupted exception");
			throw new AdapterException();
		}

		ipam.getDeviceList().add(device);

		try {
			if (authenticate(device.getName())) {
				processDevice();
				if (device.getCardList().size() > 0) {
					processVlan();
				}
			} else {
				ipam.getDeviceList().remove(device);
			}

		} catch (TopologyException e) {
			log.error("error in tl1 " );
			telnetService = null;
			throw new AdapterException();
		} finally {

			managerTelnet.returnConnection(telnetService);
		}

		return ipam;
	}

	private boolean authenticate(String name) throws TopologyException {
		try {
			String tagId = "ID0004";

			String nepassword = propertiesContainer
					.getProperty("NE_TOPOLOGY_PASSWORD");
			String neuser = propertiesContainer
					.getProperty("NE_TOPOLOGY_USERNAME");
			nepassword = EncryptDecrypt.decryptPassword(nepassword);
			String str_authenticate_command = "ACT-USER:" + name + ":" + neuser
					+ ":" + tagId + "::" + nepassword + ";";
			String resutlAuthentication = "";
			resutlAuthentication = telnetService.sendCommand(
					str_authenticate_command, tagId);
			log.debug("resutlAuthentication " + resutlAuthentication);

		} catch (Exception e) {
			log.error("authentication error on device :" + name);
			return false;
		}

		return true;
	}

	private void processDevice() throws TopologyException {

		String device_name = device.getName();

		String tagId = "ID0005";

		String str_card_command = "RTRV-INV-EQPT:" + device_name + ":ALL:"
				+ tagId + ";";
		String str_result_card = telnetService.sendCommand(str_card_command,
				tagId);
		Set<Card> setCards = parseResultCardsDetails(str_result_card, device);
		log.debug("getDevices: set cards size = " + setCards.size());

		if (setCards.size() > 0) {

			String tagPortId = "ID0006";
			String str_port_command = "";
			String str_result_ports = "";
			if (b_discover_only_ethernet) {
				str_port_command = "RTRV-GPONPORT:" + device_name + ":ALL:"
						+ tagPortId + ";";
				str_result_ports = telnetService.sendCommand(str_port_command,
						tagPortId);
				parseResultGPonPorts(str_result_ports, setCards, device_name);
			}

			str_port_command = "RTRV-LANXPORT:" + device_name + ":ALL:"
					+ tagPortId + ";";
			str_result_ports = telnetService.sendCommand(str_port_command,
					tagPortId);
			parseResultEthPorts(str_result_ports, setCards, device_name);
		}

	}


	private Set<Card> parseResultCardsDetails(String str_result_cards,
			Device device) throws TopologyException {
		Set<Card> setCards = device.getCardList();
		String str = "";
		log.debug("output : " + str_result_cards);
		try {
			StringReader reader = new StringReader(str_result_cards);
			BufferedReader bf = new BufferedReader(reader);
			boolean b_can_read = false;
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
					} while (str != null && !str.endsWith("\"")
							&& !str.equalsIgnoreCase("null"));

					if (str != null && !str.equalsIgnoreCase("null")) {
						String[] items = str.split(",");
						if (items.length == 7 || items.length == 8) {

							String serialNumber = items[5];
							String type = items[0].split(":")[1];
							String name = items[0].split(":")[0];

							Card card = new Card();
							if (name.indexOf("NTA") != -1) {

								card.setShelfNumber("1");
								card.setSlotNumber("10");

							} else if (name.indexOf("NTB") != -1) {

								card.setShelfNumber("1");
								card.setSlotNumber("12");

							} else if (name.indexOf("ACU") != -1) {

								card.setShelfNumber("1");
								card.setSlotNumber("11");

							} else if (name.contains("-")) {

								String attr[] = name.split("-");
								if (attr.length == 3) {

									card.setShelfNumber(attr[2]);
									card.setSlotNumber("0");
								} else if (attr.length == 4) {

									card.setShelfNumber(attr[2]);
									int slotNumber = Integer.parseInt(attr[3]);
									if (slotNumber > 9)
										slotNumber = slotNumber + 3;
									card.setSlotNumber(slotNumber + "");
								}
							}

							log.info("TL1: parseResultCards: card found name = "
									+ name
									+ ", type = "
									+ type
									+ " for device " + device.getName());

							String key = card.getShelfNumber() + "/"
									+ card.getSlotNumber();
							card.setStatus("UP");
							card.setLocation("DeviceId=" + device.getName());
							card.setName(key);
							card.setDescription("");
							card.setType(type);
							card.setSerialNumber(serialNumber);

							setCards.add(card);

						} else {
							log.debug("parseResultCards: line decomposed is NOT 7 tokens: "
									+ str);
						}
					}
				} while (str != null && !str.equalsIgnoreCase("null"));
			}
			device.setCardList(setCards);
		} catch (Exception e) {

			log.error("TL1: parseResultCards: error while parsing cards " + str);
			throw new TopologyException();
		}

		return setCards;
	}

	private Set<Port> parseResultGPonPorts(String str_result_ports,
			Set<Card> cards, String device_id) throws TopologyException {
		Set<Port> setPorts = new HashSet<Port>();
		String str = "";
		log.debug("output : " + str_result_ports);
		try {
			StringReader reader = new StringReader(str_result_ports);
			BufferedReader bf = new BufferedReader(reader);
			boolean b_can_read = false;

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
					} while (str != null && !str.endsWith("\"")
							&& !str.equalsIgnoreCase("null"));
					if (str != null && !str.equalsIgnoreCase("null")) {
						String[] items = str.split(",");

						if (items.length == 7) {

							String portName = items[0].split("::")[0];
							String cardName = items[0].split("::")[1];
							String speed = items[4];
							String status = items[6];
							Port port = new Port();
							if (portName.contains("-")) {

								String attr[] = portName.split("-");
								if (attr.length == 5) {

									port.setShelfNumber(attr[2]);
									int slotNumber = Integer.parseInt(attr[3]);
									if (slotNumber > 9)
										slotNumber = slotNumber + 3;
									port.setSlotNumber(slotNumber + "");
									port.setPortNumber(attr[4]);

									String key = port.getShelfNumber() + "/"
											+ port.getSlotNumber() + "/"
											+ port.getPortNumber();
									port.setName(key);
									port.setPortKey(key);

									port.setType("GPON");

									port.setMaxSpeed(speed.substring(speed
											.lastIndexOf("=") + 1));
									//davide alu
									//if(key.equalsIgnoreCase("1/1/1")) status ="OOS-AUMA";
									if (status.indexOf("IS-NR") != -1) {
										// IS-NR ( Operational status "UP",
										// Admin status "UP")
										port.setOperStatus("UP");
										port.setAdminStatus("UP");
									} else if (status.indexOf("OOS-AUMA") != -1) {
										// OOS-AUMA (Operational status "DOWN",
										// Admin status "DOWN")
										port.setOperStatus("DOWN");
										port.setAdminStatus("DOWN");
									
									} else if (status.indexOf("OOS-AU") != -1) {
										// OOS-AU (Operational status "DOWN",
										// Admin status "UP")
										port.setOperStatus("DOWN");
										port.setAdminStatus("UP");
									}

									setPorts.add(port);

									Card cardTemp = new Card();

									cardTemp.setShelfNumber(port
											.getShelfNumber());
									cardTemp.setSlotNumber(port.getSlotNumber());
									Card card = findCard(cards, cardTemp);
									if (card != null) {
										card.getPortList().add(port);
										log.info("TL1: parseResultGPONPorts: port found  "

												+ port.getName()
												+ " on card "
												+ card.getName()
												+ " for device " + device_id);

									} else {
										log.warn("parseResultGPONPorts: Card not found for port:  "

												+ port.getName()
												+ " for device " + device_id);
									}

								} else {
									log.debug("string has not correct format"
											+ str);

								}
							} else {
								log.debug("string has not correct format" + str);

							}

						} else {
							log.debug("parseResultAllPorts: line decomposed is NOT 7 tokens: "
									+ str);
						}

					}
				} while (str != null && !str.equalsIgnoreCase("null"));
			}
		} catch (Exception e) {

			log.error("TL1: parseResultAllPorts: error while parsing ports "
					+ str);
			throw new TopologyException();
		}

		return setPorts;
	}

	private Set<Port> parseResultEthPorts(String str_result_ports,
			Set<Card> cards, String device_id) throws TopologyException {
		Set<Port> setPorts = new HashSet<Port>();
		String str = "";
		log.debug("output : " + str_result_ports);
		try {
			StringReader reader = new StringReader(str_result_ports);
			BufferedReader bf = new BufferedReader(reader);
			boolean b_can_read = false;

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
					} while (str != null && !str.endsWith("\"")
							&& !str.equalsIgnoreCase("null"));
					if (str != null && !str.equalsIgnoreCase("null")) {
						String[] items = str.split(",");

						if (items.length == 8) {

							String name = items[0].split("::")[0].trim()
									.replace("\"", "");
							String cardName = items[0].split("::")[1]
									.substring(items[0].split("::")[1]
											.indexOf("=") + 1);
							String slotNumber = items[1].substring(items[1]
									.indexOf("=") + 1);
							String descr = items[2].substring(items[2]
									.indexOf("=") + 1);
							String speed = items[4].substring(items[4]
									.indexOf("=") + 1);
							String portNumber = items[3]
									.replace("\"", "")
									.substring(
											items[3].replace("\"", "")
													.lastIndexOf(" ") + 1)
									.replace("\\", "");
							String status = items[7].replace(" ", "");

							Port port = new Port();
							if (cardName.equalsIgnoreCase("NTA")) {

								port.setShelfNumber("1");
								port.setSlotNumber("10");
							} else if (cardName.equalsIgnoreCase("NTB")) {

								port.setShelfNumber("1");
								port.setSlotNumber("12");
							}

							port.setPortNumber(portNumber);

							String key = port.getShelfNumber() + "/"
									+ port.getSlotNumber() + "/"
									+ port.getPortNumber();
							port.setName(key);
							port.setPortKey(key);
							port.setTag(name);
							port.setDescription(descr.replace("\"", "")
									.replace("\\", ""));

							port.setMaxSpeed(speed);
							//davide alu
							//if(key.equalsIgnoreCase("1/1/1")) 
							///	status ="OOS-AUMA";
							if (status.indexOf("IS-NR") != -1) {
								// IS-NR ( Operational status "UP",
								// Admin status "UP")
								port.setOperStatus("UP");
								port.setAdminStatus("UP");
							} else if (status.indexOf("OOS-AUMA") != -1) {
								// OOS-AUMA (Operational status "DOWN",
								// Admin status "DOWN")
								port.setOperStatus("DOWN");
								port.setAdminStatus("DOWN");
							
							} else if (status.indexOf("OOS-AU") != -1) {
								// OOS-AU (Operational status "DOWN",
								// Admin status "UP")
								port.setOperStatus("DOWN");
								port.setAdminStatus("UP");
							}

							// davide
							//if (port.getPortNumber().equalsIgnoreCase("4")){
							// port.setOperStatus("DOWN");
							// port.setAdminStatus("DOWN");}
							Card cardTemp = new Card();

							cardTemp.setShelfNumber(port.getShelfNumber());
							cardTemp.setSlotNumber(port.getSlotNumber());

							Card card = findCard(cards, cardTemp);
							if (card != null) {
								boolean isadded = card.getPortList().add(port);
								if (isadded)
									log.info("TL1: parseResultETHPorts: port found  "

											+ port.getName()
											+ " tag "
											+ port.getTag()
											+ " on card "
											+ card.getName()

											+ " for device " + device_id);

							} else {
								log.warn("parseResultETHPorts: Card not found for port:  "

										+ port.getName()
										+ " for device "
										+ device_id);
							}

						} else {
							log.debug("parseResultETHPorts: line decomposed is NOT 8 tokens: "
									+ str);
						}

					}
				} while (str != null && !str.equalsIgnoreCase("null"));
			}
		} catch (Exception e) {

			log.error("TL1: parseResultAllPorts: error while parsing ports "
					+ str);
			throw new TopologyException();
		}

		return setPorts;
	}

	private Card findCard(Set<Card> cards, Card tempCard) {
		if (cards != null) {
			for (Card card : cards) {
				if (card.equals(tempCard))
					return card;
			}
		}
		return null;
	}

	private void processVlan() throws TopologyException {
		try {

			Set<VLan> setVlan = ipam.getVlanList();

			String device_name = device.getName();
			String tagVLanId = "ID0007";
			String str_vlan_command = "RTRV-LANXVLAN:" + device_name + ":ALL:"
					+ tagVLanId + ";";
			String str_result_vlans = telnetService.sendCommand(
					str_vlan_command, tagVLanId);
			log.debug("======\n"+str_result_vlans+"=========");
			Map<String, VLan> outputVlans = new HashMap<String, VLan>();
			parseResultVlansOfDevice(outputVlans, str_result_vlans, device);
			setVlan.addAll(outputVlans.values());

		} catch (TopologyException e) {
			log.error("impossible create a connection with the server");
		}
		return;
	}

	private void parseResultVlansOfDevice(Map<String, VLan> outputVlans,
			String str_result_vlans, Device device) throws TopologyException {
		String str = "";
		try {
			StringReader reader = new StringReader(str_result_vlans);
			BufferedReader bf = new BufferedReader(reader);
			boolean b_can_read = false;

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
						if(str.indexOf("LANX")==-1 && str.indexOf("null")==-1){str = ""; }
						
					} while (str != null && !str.endsWith("\"")
							&& !str.endsWith("null"));
					if (str != null && !str.endsWith("null")) {
						String[] items = str.split(",");

						if (items.length == 5) {
							String name = items[0].split("::")[0].trim()
									.replace("\"", "");

							VLan vlan = null;
							if (outputVlans.containsKey(name)) {
								vlan = outputVlans.get(name);
								vlan.getDeviceNameList().add(
										device.getId() + "");

								log.debug("parseResultVlansOfDevice: vlan found, add device to vlan: "
										+ name);
							} else {
								log.debug("parseResultVlansOfDevice: creating new vlan: "
										+ name);
								vlan = new VLan();

								vlan.setName("VLAN-" + name);
								vlan.setVLANkey(name);
								vlan.getDeviceNameList().add(
										device.getName() + "");
								outputVlans.put(name, vlan);

							}

						} else {
							log.debug("parseResultVlansOfDevice: line decomposed is NOT 5 tokens: "
									+ str);
						}

					}
				} while (str != null && !str.endsWith("null"));
			}
		} catch (Exception e) {

			log.error("TL1: parseResultVlansOfDevice: error while parsing "
					+ str);
			throw new TopologyException();
		}

	}

	public Set<Card> processCard(String device_name, TelnetService telnetService)
			throws TopologyException, AdapterException {

		String tagId = "ID0005";
		this.telnetService = telnetService;
		if (authenticate(device_name)) {

			String str_card_command = "RTRV-INV-EQPT:" + device_name + ":ALL:"
					+ tagId + ";";

			String str_result_card = "";
			int numberConnection = 0;

			while (str_result_card.equalsIgnoreCase("")) {

				str_result_card = telnetService.sendCommand(str_card_command,
						tagId);

				numberConnection++;
				if (numberConnection > maxConnection) {
					throw new AdapterException(1);
				}
			}
			Set<Card> setCards = parseResultCardsDetails(str_result_card,
					new Device());
			return setCards;
		} else
			return null;

	}

}
