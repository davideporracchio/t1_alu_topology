package com.holonomix.tl1;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;

import com.holonomix.commoninterface.TopologyAdapterInterface;
import com.holonomix.exception.AdapterException;
import com.holonomix.exception.TopologyException;
import com.holonomix.hsqldb.model.Card;
import com.holonomix.hsqldb.model.Chassis;
import com.holonomix.hsqldb.model.Device;
import com.holonomix.hsqldb.model.Ip;
import com.holonomix.hsqldb.model.Ipam;
import com.holonomix.hsqldb.model.NetworkConnection;

import com.holonomix.hsqldb.model.SNMPAgent;
import com.holonomix.hsqldb.model.VLan;
import com.holonomix.hsqldb.model.utility.EMSDeviceService;
import com.holonomix.properties.PropertiesContainer;
import com.holonomix.tl1.task.TL1DeviceCallable;
import com.holonomix.tl1.task.TL1VlanCallable;
import com.holonomix.tl1.utility.UtilityTL1;

public class TL1 implements TopologyAdapterInterface {

	public static int PORT_GPON = 1;
	public static int PORT_ETHERNET = 2;

	TelnetService telnetService = null;
	boolean b_discover_only_ethernet = false;
	private static final Logger log = Logger.getLogger(TL1.class);
	PropertiesContainer propertiesContainer = null;
	Map<String, String> deviceProperty = new HashMap<String, String>();
	UtilityTL1 utilityTL1;
	ManagerTelnet managerTelnet = null;
	int maxConnection;

	public void getDevicesAndVlans(Map<String, Device> outputDevices,
			Map<String, VLan> outputVlans, Set<String> filterDevices) {
		outputDevices.clear();
		outputVlans.clear();
		propertiesContainer = PropertiesContainer.getInstance();
		try {
			initialise();
			maxConnection = Integer.parseInt(propertiesContainer
					.getProperty("TOPOLOGY_RETRY_NUMBER"));
			managerTelnet = ManagerTelnet.getInstance();
			managerTelnet.createConnections();
			utilityTL1 = new UtilityTL1();

			process(outputDevices, outputVlans, filterDevices);
			// this code if for test purpose
			if (propertiesContainer.getProperty("NUMBER_DEVICES_SIMULATION") != null) {
				int num = Integer.parseInt(propertiesContainer
						.getProperty("NUMBER_DEVICES_SIMULATION"));
				UtilityTL1.duplicateEntries(outputDevices, num);
			}

		} catch (AdapterException e2) {

			log.error("adapter exception");
			outputDevices.clear();
			outputVlans.clear();
			return;

		} catch (InterruptedException e) {

			log.error("interrupted exception");

			outputDevices.clear();
			outputVlans.clear();
			return;

		} finally {
			managerTelnet.disconnectConnections();
			if (managerTelnet != null)
				managerTelnet = null;
		}
	}

	public boolean initialise() throws AdapterException {

		String deviceMap = propertiesContainer.getProperty("EMS_DEVICEMAP");
		for (String device : deviceMap.split(",")) {
			deviceProperty.put(device.split("::")[0], device.split("::")[1]);
		}

		return true;
	}

	private void process(Map<String, Device> outputDevices,
			Map<String, VLan> outputVlans, Set<String> filterDevices)
			throws InterruptedException, AdapterException {

		log.info("TL1: getDevices: start inventory");
		// if list is null import everything
		String str_result_device = "";
		int numberConnection = 0;
		while (str_result_device.equalsIgnoreCase("")) {
			try {
				numberConnection++;

				telnetService = managerTelnet.getConnection();
				if (telnetService == null)
					throw new TopologyException();
				str_result_device = telnetService.sendCommand(
						"RTRV-GW-NEINFO:TNM1:ALL:ID002;", "ID002");
				log.debug("output " + str_result_device);
				managerTelnet.returnConnection(telnetService);
			} catch (TopologyException e) {
				telnetService = null;
				managerTelnet.returnConnection(telnetService);
				if (numberConnection < maxConnection) {

				} else if (numberConnection > maxConnection) {
					throw new AdapterException(1);
				}

			}

		}
		List<String> deviceNoInSeedFile = new ArrayList<String>();
		parseResultDevices(outputDevices, str_result_device, filterDevices,
				deviceNoInSeedFile);
		log.debug("getDevices: outputDevices size = " + outputDevices.size());
		int numberThreads = Integer.parseInt(propertiesContainer
				.getProperty("NUMBER_THREADS"));
		log.debug("numberThreads " + numberThreads);
		if (outputDevices != null) {
			// create thread for each device

			ThreadPoolExecutor tpe = new ThreadPoolExecutor(numberThreads,
					numberThreads, 50000L, TimeUnit.MILLISECONDS,
					new LinkedBlockingQueue<Runnable>());

			FutureTask<Ipam>[] tasks = new FutureTask[outputDevices.size()];

			List<Device> deviceToExecute = new ArrayList<Device>(
					outputDevices.values());
			numberConnection = 0;

			Map<Integer, Device> taskDevice = new HashMap<Integer, Device>();
			List<Ipam> listIpam = new ArrayList<Ipam>();
			while (deviceToExecute.size() > 0
					&& numberConnection < maxConnection) {
				runFutureTaskDevice(deviceToExecute, tpe, tasks, taskDevice);

				waitFutureTaskDevice(tasks);

				resultTaskDevice(listIpam, deviceToExecute, tasks, taskDevice);
				numberConnection++;
				if (deviceToExecute.size() > 0
						&& numberConnection == maxConnection) {
					throw new AdapterException(1);
				}
			}

			tpe.shutdown();
			tpe.awaitTermination(1000, TimeUnit.MILLISECONDS);

			// vlan code
			outputDevices.clear();
			for (Ipam ipam : listIpam) {
				for (Device device : ipam.getDeviceList()) {
					if (device != null)
						outputDevices.put(device.getName(), device);
				}
				for (VLan vlan : ipam.getVlanList()) {
					if (vlan.getDeviceNameList().size() != 0) {
						VLan vlanTemp = null;
						if (outputVlans.containsKey(vlan.getVLANkey())) {
							vlanTemp = outputVlans.get(vlan.getVLANkey());
							vlanTemp.getDeviceNameList().addAll(
									vlan.getDeviceNameList());
						} else {
							outputVlans.put(vlan.getVLANkey(), vlan);
						}
					}
				}
			}
			for (String name : deviceNoInSeedFile) {
				outputDevices.put(name, null);
			}
			Ipam ipam = new Ipam();
			ipam.getDeviceList().addAll(outputDevices.values());
			ipam.getVlanList().addAll(outputVlans.values());
			numberConnection = 0;

			tpe = new ThreadPoolExecutor(numberThreads, numberThreads, 50000L,
					TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());

			List<VLan> vlanToExecute = new ArrayList<VLan>(outputVlans.values());
			FutureTask<VLan>[] tasksVlan = new FutureTask[outputVlans.size()];
			Map<Integer, VLan> taskVLanMap = new HashMap<Integer, VLan>();
			List<VLan> listVLan = new ArrayList<VLan>();
			while (vlanToExecute.size() > 0 && numberConnection < maxConnection) {
				runFutureTaskVlan(vlanToExecute, tpe, tasksVlan, taskVLanMap,
						ipam);

				waitFutureTaskVlan(tasksVlan);

				resultTaskVlan(listVLan, vlanToExecute, tasksVlan, taskVLanMap);
				numberConnection++;

				if (vlanToExecute.size() > 0
						&& numberConnection < maxConnection) {

				} else if (vlanToExecute.size() > 0
						&& numberConnection > maxConnection) {
					throw new AdapterException(1);
				}
			}

			tpe.shutdown();
			tpe.awaitTermination(1000, TimeUnit.MILLISECONDS);

		}

	}

	private void runFutureTaskDevice(List<Device> deviceToExecute,
			ThreadPoolExecutor tpe, FutureTask<Ipam>[] tasks,
			Map<Integer, Device> taskDevice) {
		for (int j = 0; j < tasks.length; j++)
			tasks[j] = null;
		taskDevice.clear();
		int i = 0;
		for (Device device : deviceToExecute) {
			tasks[i] = new FutureTask<Ipam>(new TL1DeviceCallable(device));
			tpe.execute(tasks[i]);
			taskDevice.put(i, device);
			i++;
		}
	}

	private void waitFutureTaskDevice(FutureTask<Ipam>[] tasks) {
		for (int i = 0; i < tasks.length; i++) {

			if (tasks[i] != null) {
				while (!tasks[i].isDone()) {
					// log.debug("Task not yet completed.");
					try {
						Thread.sleep(1000);
					} catch (InterruptedException ie) {
						log.error("Interrupted");
					}
				}
			}
		}
	}

	private void resultTaskDevice(List<Ipam> listIpam,
			List<Device> deviceToExecute, FutureTask<Ipam>[] tasks,
			Map<Integer, Device> taskDevice) {
		deviceToExecute.clear();

		for (int i = 0; i < tasks.length; i++) {

			if (tasks[i] != null) {
				try {
					Ipam result = tasks[i].get();

					listIpam.add(result);
				} catch (Exception e) {

					deviceToExecute.add(taskDevice.get(i));
					log.error("error ipam " + taskDevice.get(i).getName());
				}
			}
		}

	}

	private void runFutureTaskVlan(List<VLan> vlanToExecute,
			ThreadPoolExecutor tpe, FutureTask<VLan>[] tasks,
			Map<Integer, VLan> taskVlan, Ipam ipam) {
		for (int j = 0; j < tasks.length; j++)
			tasks[j] = null;
		taskVlan.clear();
		int i = 0;
		for (VLan vlan : vlanToExecute) {
			tasks[i] = new FutureTask<VLan>(new TL1VlanCallable(ipam, vlan));
			tpe.execute(tasks[i]);
			taskVlan.put(i, vlan);
			i++;
		}
	}

	private void waitFutureTaskVlan(FutureTask<VLan>[] tasks) {
		for (int i = 0; i < tasks.length; i++) {

			if (tasks[i] != null) {
				while (!tasks[i].isDone()) {
					// log.debug("Task not yet completed.");
					try {
						Thread.sleep(1000);
					} catch (InterruptedException ie) {
						log.error("Interrupted");
					}
				}
			}
		}
	}

	private void resultTaskVlan(List<VLan> listVlan, List<VLan> vlanToExecute,
			FutureTask<VLan>[] tasks, Map<Integer, VLan> taskVlanMap) {
		vlanToExecute.clear();

		for (int i = 0; i < tasks.length; i++) {

			if (tasks[i] != null) {
				try {
					VLan result = tasks[i].get();

					listVlan.add(result);
				} catch (Exception e) {
					vlanToExecute.add(taskVlanMap.get(i));
					log.error("error ipam " + taskVlanMap.get(i).getName());
				}
			}
		}

	}

	public void parseResultDevices(Map<String, Device> outputDevices,
			String str_result_device, Set<String> filterDevice,
			List<String> deviceNoInSeedFile) throws AdapterException {
		// Map<String, Device> mapDevices = new HashMap<String, Device>();
		String str = "";
		List<String> listEmsDevice = new ArrayList<String>();
		try {
			// log.debug("parseResultDevices 1: " + str_result_device);
			StringReader reader = new StringReader(str_result_device);
			BufferedReader bf = new BufferedReader(reader);
			boolean b_can_read = false;

			do {
				str = bf.readLine();
				if (str.indexOf("COMPLD") != -1) {
					b_can_read = true;
					break;
				}
			} while (str != null);

			if (b_can_read) {

				do {
					str = bf.readLine();
					if (str != null) {
						String[] items = str.split(",");
						// log.debug("parseResultDevices: after decompose: item: "
						// + item);
						if (items.length == 6) {

							String name = items[0].split(":")[1];
							String ipAddress = items[1];
							listEmsDevice.add(name);

							if (deviceProperty.containsKey(propertiesContainer
									.getProperty("DEVICE_MODEL"))) {
								boolean b_allowed = true;
								if ((filterDevice != null)
										&& (filterDevice.size() != 0)) {
									if (!filterDevice.contains(name)) {
										b_allowed = false;
									}
								}
								if (b_allowed) {
									Device device = new Device();
									device.setModel(propertiesContainer
											.getProperty("DEVICE_MODEL"));
									device.setName(name);
									device.setVendor(propertiesContainer
											.getProperty("VENDOR"));
									device.setCreationClassName(deviceProperty
											.get(device.getModel()));
									device.setType(Device.CLASSNAMESWITCH);
									//device.setDescription("EMC-Adapter :");
									// create chassis
									Chassis chassis = new Chassis();
									chassis.setName(name);
									chassis.setDisplayName(name);
									device.getChassisList().add(chassis);
									// create snmpagent
									SNMPAgent snmpAgent = new SNMPAgent();
									snmpAgent.setName("SNMPAgent-" + name);
									snmpAgent.setDisplayName("SNMPAgent-"
											+ name);
									device.getSnmpAgentList().add(snmpAgent);

									Ip ip = new Ip();
									ip.setAddress(ipAddress);
									device.getIpList().add(ip);

									outputDevices.put(name, device);
									log.info("TL1: parseResultDevices: device found id = "
											+ name + ", name = " + name);
								} else {
									// not in seed file
									log.warn("device with name " + name
											+ " not present in seed file");
									deviceNoInSeedFile.add(name);

								}
							}
						} else {
							log.debug("parseResultDevices: line decomposed is NOT 6 tokens: "
									+ items);
						}
					}
				} while (str != null);
			}
		} catch (Exception e) {

			log.error("TL1: parseResultDevices: error while parsing devices "
					+ str);
			throw new AdapterException(1);
		}

		return;
	}

	@Override
	public void getNetworkConnection(List<NetworkConnection> networkConnections)
			throws AdapterException {

	}

	@Override
	public void checkCardStatus(Set<Card> setCard) throws AdapterException {

		propertiesContainer = PropertiesContainer.getInstance();
		try {

			maxConnection = Integer.parseInt(propertiesContainer
					.getProperty("TOPOLOGY_RETRY_NUMBER"));
			managerTelnet = ManagerTelnet.getInstance();
			managerTelnet.createConnections();
			utilityTL1 = new UtilityTL1();

			telnetService = managerTelnet.getConnection();
			String str_result_card = "";
			Map<String, Set<Card>> mapCards = new HashMap<String, Set<Card>>();
			for (Card card : setCard) {
				String deviceId = card.getLocation().replace("DeviceId=", "");
				Set<Card> setCardMap = new HashSet<Card>();
				if (mapCards.containsKey(deviceId)) {
					setCardMap = mapCards.get(deviceId);
				}
				setCardMap.add(card);
				mapCards.put(deviceId, setCardMap);

			}
			setCard.clear();
			TL1DeviceCallable tl1DeviceCallable = new TL1DeviceCallable(null);
			for (String deviceId : mapCards.keySet()) {
				
					Set<Card> setCardEms = tl1DeviceCallable.processCard(
							deviceId, telnetService);

					for (Card cardSmart : mapCards.get(deviceId)) {
						cardSmart.splitName(cardSmart.getName().substring(
								cardSmart.getName().indexOf("/") + 1));
						// if cardSmart is not present in the list of cards we
						// have in EMS it means that card is still down
						boolean foundcard = false;
						for (Card cardEms : setCardEms) {
							if (cardSmart.equals(cardEms)) {
								foundcard = true;
							}
						}
						if (foundcard == false) {
							// if it is not in ems it means it is still offline
							// and we keep in topologycollection groupcardSmart
							setCard.add(cardSmart);

						}

					}
				
			}

		} catch (InterruptedException e) {

			throw new AdapterException(1);

		} catch (NumberFormatException e) {
			// TODO Auto-generated catch block
			throw new AdapterException(1);
		} catch (TopologyException e) {
			// TODO Auto-generated catch block
			throw new AdapterException(1);
		} finally {
			managerTelnet.disconnectConnections();
			if (managerTelnet != null)
				managerTelnet = null;
		}

	}

}
