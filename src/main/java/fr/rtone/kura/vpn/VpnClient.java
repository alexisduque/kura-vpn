package fr.rtone.kura.vpn;

import org.eclipse.kura.KuraException;
import org.eclipse.kura.cloud.Cloudlet;
import org.eclipse.kura.cloud.CloudletTopic;
import org.eclipse.kura.configuration.ConfigurableComponent;
import org.eclipse.kura.data.DataTransportService;
import org.eclipse.kura.message.KuraRequestPayload;
import org.eclipse.kura.message.KuraResponsePayload;
import fr.rtone.kura.vpn.VpnService;
import fr.rtone.kura.vpn.impl.OpenVpnServiceImpl;

import java.io.PrintStream;
import java.util.Map;

import org.eclipse.kura.message.KuraResponsePayload;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VpnClient extends Cloudlet implements ConfigurableComponent {
	private static final Logger logger = LoggerFactory
			.getLogger(VpnClient.class);
	private static final String CLOUD_APP_ID = "VPNCLIENT-V1";
	private static final String USERNAME_PROP_NAME = "username";
	private static final String PASSWORD_PROP_NAME = "password";
	private Map<String, Object> properties;
	private DataTransportService dataTransportService;
	private VpnService vpnService = new OpenVpnServiceImpl();

	public VpnClient() {
		super(CLOUD_APP_ID);
	}

	public void setDataTransportService(
			DataTransportService dataTransportService) {
		this.dataTransportService = dataTransportService;
	}

	public void unsetDataTransportService(
			DataTransportService dataTransportService) {
		dataTransportService = null;
	}

	protected void activate(ComponentContext componentContext,
			Map<String, Object> properties) {
		logger.info("Activating VPN Bundle...");

		this.properties = properties;
		for (String s : properties.keySet()) {
			logger.info("Activate - " + s + ": " + properties.get(s));
		}
		super.activate(componentContext);

		logger.info("VPN Bundle activated");
	}

	protected void deactivate(ComponentContext componentContext) {
		logger.debug("Deactivating VPN Bundle ...");

		super.deactivate(componentContext);

		logger.debug("Deactivated");
	}

	public void updated(Map<String, Object> properties) {
		logger.info("Updating VPN Bundle...");

		this.properties = properties;
		for (String s : properties.keySet()) {
			logger.info("Update - " + s + ": " + properties.get(s));
		}
		logger.info("Updated.");
	}

	protected void doGet(CloudletTopic reqTopic, KuraRequestPayload reqPayload,
			KuraResponsePayload respPayload) throws KuraException {
		String[] resources = reqTopic.getResources();
		if (!resources[0].equals("status")) {
			respPayload.setResponseCode(400);
			return;
		}
		fillResponse(respPayload);
	}

	protected void doExec(CloudletTopic reqTopic,
			KuraRequestPayload reqPayload, KuraResponsePayload respPayload)
			throws KuraException {
		String[] resources = reqTopic.getResources();
		boolean start = false;
		if ((!(start = resources[0].equals("connect")))
				&& (!resources[0].equals("disconnect"))) {
			respPayload.setResponseCode(400);
			return;
		}
		if (start) {
			String username = (String) properties.get(USERNAME_PROP_NAME);
			String clientId = dataTransportService.getClientId();
			username = username + "/" + clientId;
			logger.debug("Cloud clientId : " + clientId);
			String password = (String) properties.get(PASSWORD_PROP_NAME);
			vpnService.setConfiguration((byte[]) reqPayload
					.getMetric("configuration"));
			vpnService.start(username, password);
		} else {
			vpnService.stop();
		}
		fillResponse(respPayload);
	}

	private void fillResponse(KuraResponsePayload respPayload) {
		boolean connected = true;
		String ipAddress = vpnService.getNetInterfaceIpAddress();
		if (ipAddress == null) {
			connected = false;
			ipAddress = "0.0.0.0";
		}
		respPayload.addMetric("connected", Boolean.valueOf(connected));
		respPayload.addMetric("ip.address", ipAddress);
	}
}